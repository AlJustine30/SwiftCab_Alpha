
// V2 Imports
const { onValueCreated } = require("firebase-functions/v2/database");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { setGlobalOptions } = require("firebase-functions/v2");
const { logger } = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.database();
const firestore = admin.firestore();
const authAdmin = admin.auth();
const rtdbAdmin = admin.database();

setGlobalOptions({
  region: "us-central1",
  serviceAccount: "323933258887-compute@developer.gserviceaccount.com",
});

/**
 * Archive a booking when its status becomes COMPLETED.
 * Triggered on status updates rather than creation.
 */
exports.archiveBooking = require("firebase-functions/v2/database").onValueUpdated("/bookingRequests/{bookingId}/status", async (event) => {
    const newStatusSnap = event.data.after; // RTDB snapshot of the status node
    const oldStatusSnap = event.data.before;
    const bookingId = event.params.bookingId;

    if (!newStatusSnap.exists()) return null;
    const newStatus = newStatusSnap.val();
    const oldStatus = oldStatusSnap.exists() ? oldStatusSnap.val() : null;

    if (newStatus === "COMPLETED" && oldStatus !== "COMPLETED") {
        try {
            // Read full booking data from parent node
            const bookingRef = newStatusSnap.ref.parent; // /bookingRequests/{bookingId}
            const bookingSnapshot = await bookingRef.once("value");
            const bookingData = bookingSnapshot.val();
            if (!bookingData) return null;

            // Write to the collection used by the apps for history views
            const historyDoc = {
                ...bookingData,
                // Ensure status is finalized and timestamp exists for ordering/indexes
                status: "COMPLETED",
                timestamp: typeof bookingData.timestamp === "number" ? bookingData.timestamp : (bookingData.tripEndedAt || Date.now()),
            };
            await firestore.collection("bookinghistory").doc(bookingId).set(historyDoc);

            // Award loyalty points to rider: 1 point per ₱100 spent
            try {
                const riderId = bookingData.riderId;
                if (riderId) {
                    const fareAmount = typeof bookingData.finalFare === "number"
                        ? bookingData.finalFare
                        : (typeof bookingData.estimatedFare === "number" ? bookingData.estimatedFare : 0);
                    const points = Math.floor(fareAmount / 100);
                    if (points > 0) {
                        await firestore.collection("users").doc(riderId)
                            .update({ loyaltyPoints: admin.firestore.FieldValue.increment(points) });
                        logger.log(`Awarded ${points} loyalty points to rider ${riderId} for booking ${bookingId}`);
                    } else {
                        logger.log(`No loyalty points awarded (fare=${fareAmount}) for booking ${bookingId}`);
                    }
                } else {
                    logger.warn(`archiveBooking: missing riderId for booking ${bookingId}; cannot award points.`);
                }
            } catch (e) {
                logger.error(`Failed to award loyalty points for booking ${bookingId}:`, e);
            }

            await bookingRef.remove();
            logger.log(`Archived booking ${bookingId} to Firestore and removed from RTDB.`);
        } catch (error) {
            logger.error(`archiveBooking error for ${bookingId}:`, error);
        }
    }
    return null;
});


// archiveBooking is now handled by an onValueUpdated trigger on '/bookingRequests/{bookingId}/status' above.
// Removing legacy onValueCreated handler to avoid duplicate exports.

/** Utility to normalize history docs */
function sanitizeHistoryDoc(data) {
  if (!data) return {};
  const ts = typeof data.timestamp === "number" ? data.timestamp : (data.tripEndedAt || Date.now());
  return {
    ...data,
    status: data.status || "COMPLETED",
    timestamp: ts,
  };
}

/**
 * Callable backfill: copy all docs from completedBookings -> bookinghistory
 */
exports.backfillCompletedToHistory = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "You must be logged in to run backfill.");
  }
  try {
    const snap = await firestore.collection("completedBookings").get();
    let written = 0;
    const batch = firestore.batch();
    snap.forEach((doc) => {
      const data = sanitizeHistoryDoc(doc.data());
      batch.set(firestore.collection("bookinghistory").doc(doc.id), data, { merge: true });
      written++;
    });
    await batch.commit();
    logger.log(`backfillCompletedToHistory: wrote ${written} docs`);
    return { ok: true, written };
  } catch (err) {
    logger.error("backfillCompletedToHistory error", err);
    throw new HttpsError("internal", err.message || "Backfill failed");
  }
});

/**
 * Callable backfill: copy all docs from bookinghistory -> completedBookings
 */

/**
 * Save Google Maps API key to Firestore config. Admin-only callable.
 */
exports.saveGoogleMapsKey = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "You must be logged in.");
  }
  const uid = request.auth.uid;
  try {
    const userDoc = await firestore.collection("users").doc(uid).get();
    const role = userDoc.exists ? (userDoc.get("role") || "") : "";
    if (role !== "Admin") {
      throw new HttpsError("permission-denied", "Admin privileges required.");
    }
    const apiKey = (request.data && request.data.apiKey) || "";
    if (typeof apiKey !== "string" || apiKey.length < 20) {
      throw new HttpsError("invalid-argument", "Invalid API key.");
    }
    await firestore.collection("config").doc("googleMaps").set({
      apiKey,
      updatedBy: uid,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, { merge: true });
    return { ok: true };
  } catch (err) {
    if (err instanceof HttpsError) throw err;
    throw new HttpsError("internal", err.message || "Failed to save key");
  }
});


/**
 * V2 Cloud Function triggered when a new booking request is created.
 *
 * This function finds the 5 nearest online drivers and sends them a booking offer.
 */
exports.onBookingCreated = onValueCreated("/bookingRequests/{bookingId}", async (event) => {
    // The snapshot is available at event.data
    const snapshot = event.data;
    let bookingData = snapshot.val() || {};
    const bookingId = event.params.bookingId;

    // Ensure the function only runs for new bookings in the "SEARCHING" state.
    if (bookingData.status !== "SEARCHING") {
        logger.log(`Booking ${bookingId} is not in SEARCHING state, ignoring.`);
        return null;
    }

    logger.log(`New booking ${bookingId}:`, bookingData);

        try {
        const updates = {};
        try {
            const pickup = { latitude: bookingData.pickupLatitude, longitude: bookingData.pickupLongitude };
            const dropoff = { latitude: bookingData.destinationLatitude, longitude: bookingData.destinationLongitude };
            const hasDistance = typeof bookingData.distanceKm === "number";
            const hasBase = typeof bookingData.fareBase === "number";
            const hasPerKm = typeof bookingData.perKmRate === "number";
            const hasPerMin = typeof bookingData.perMinuteRate === "number";
            const hasEstimate = typeof bookingData.estimatedFare === "number";
            const distanceKm = hasDistance ? bookingData.distanceKm : getDistance(pickup, dropoff);
            const fareBase = hasBase ? bookingData.fareBase : 50;
            const perKmRate = hasPerKm ? bookingData.perKmRate : 13.5;
            const perMinuteRate = hasPerMin ? bookingData.perMinuteRate : 2;
            if (!hasDistance && Number.isFinite(distanceKm)) updates.distanceKm = distanceKm;
            if (!hasBase) updates.fareBase = fareBase;
            if (!hasPerKm) updates.perKmRate = perKmRate;
            if (!hasPerMin) updates.perMinuteRate = perMinuteRate;
            if (!hasEstimate) updates.estimatedFare = fareBase + perKmRate * (Number.isFinite(distanceKm) ? distanceKm : 0);
            if (Object.keys(updates).length > 0) {
                await snapshot.ref.update(updates);
                bookingData = { ...bookingData, ...updates };
            }
        } catch (e) {
            logger.warn(`onBookingCreated: failed to compute estimate for ${bookingId}`, e);
        }

        // Get all online drivers from the "drivers" node.
        const driversSnapshot = await db.ref("drivers").orderByChild("isOnline").equalTo(true).once("value");

        if (!driversSnapshot.exists()) {
            logger.warn("No online drivers found.");
            // Update booking to "NO_DRIVERS" if none are available.
            return snapshot.ref.update({ status: "NO_DRIVERS", cancellationReason: "NO_DRIVERS" });
        }

        const riderPickupLocation = {
            latitude: bookingData.pickupLatitude,
            longitude: bookingData.pickupLongitude,
        };

        const driversWithDistance = [];
        driversSnapshot.forEach((driverSnapshot) => {
            const driver = driverSnapshot.val();
            // Read nested driver.location first, then fallback to legacy top-level latitude/longitude
            const lat = driver?.location?.latitude ?? driver?.latitude;
            const lng = driver?.location?.longitude ?? driver?.longitude;
            if (typeof lat === "number" && typeof lng === "number") {
                const driverLocation = { latitude: lat, longitude: lng };
                const distance = getDistance(riderPickupLocation, driverLocation);
                driversWithDistance.push({ driverId: driverSnapshot.key, driver, distance });
            } else {
                // This log helps in debugging if a driver is online but has no location data.
                logger.warn(`Driver ${driverSnapshot.key} online but missing location{latitude,longitude}.`);
            }
        });

        const riderId = bookingData.riderId;
        let riderAvg = 0;
        try {
            const riderDoc = await firestore.collection("AVGrating").doc("driver_rating_summaries_" + riderId).get();
            riderAvg = riderDoc.exists ? (riderDoc.get("average") || 0) : 0;
        } catch (_) {}

        let maxCandidates = 50;
        let maxOffers = 5;
        let searchTimeoutMs = 300000;
        try {
            const cfgSnap = await db.ref("/config/matching").once("value");
            const cfg = cfgSnap.val() || {};
            if (typeof cfg.maxCandidates === "number" && cfg.maxCandidates > 0) maxCandidates = cfg.maxCandidates;
            if (typeof cfg.maxOffers === "number" && cfg.maxOffers > 0) maxOffers = cfg.maxOffers;
            if (typeof cfg.searchTimeoutMs === "number" && cfg.searchTimeoutMs > 0) searchTimeoutMs = cfg.searchTimeoutMs;
        } catch (_) {}

        const pool = driversWithDistance.sort((a, b) => a.distance - b.distance).slice(0, maxCandidates);
        const withAvg = await Promise.all(
            pool.map(async (info) => {
                try {
                    const dDoc = await firestore.collection("AVGrating").doc("driver_rating_summaries_" + info.driverId).get();
                    const avg = dDoc.exists ? (dDoc.get("average") || 0) : 0;
                    return { ...info, avg };
                } catch (_) {
                    return { ...info, avg: 0 };
                }
            })
        );

        const round = (v) => Math.round(v * 10) / 10;
        const r = round(typeof riderAvg === "number" ? riderAvg : 0);
        const exact = withAvg.filter((x) => round(x.avg) === r).sort((a, b) => a.distance - b.distance);
        let selected = exact.slice(0, maxOffers);

        if (r <= 0) {
            selected = withAvg.sort((a, b) => a.distance - b.distance).slice(0, maxOffers);
        }

        if (selected.length === 0 && r > 0) {
            const lower = withAvg.filter((x) => x.avg < r).sort((a, b) => (r - a.avg) - (r - b.avg) || a.distance - b.distance);
            if (lower.length > 0) {
                selected = lower.slice(0, maxOffers);
            } else {
                const higher = withAvg.filter((x) => x.avg > r).sort((a, b) => (a.avg - r) - (b.avg - r) || a.distance - b.distance);
                selected = higher.slice(0, maxOffers);
            }
        }

        if (selected.length === 0) {
            logger.warn("No drivers with location data found.");
            return snapshot.ref.update({ status: "NO_DRIVERS", cancellationReason: "NO_DRIVERS_NEARBY" });
        }

        logger.log(`Selected ${selected.length} drivers by rating proximity.`);

        // Create an offer for each of the closest drivers.
        const offerPromises = selected.map((driverInfo) => {
            const driverId = driverInfo.driverId;
            // The offer includes the complete booking data plus a server timestamp.
            return db.ref(`/driverOffers/${driverId}/${bookingId}`).set({
                ...bookingData,
                timestamp: admin.database.ServerValue.TIMESTAMP,
            });
        });

        await Promise.all(offerPromises);
        logger.log(`Offers sent to drivers for booking ${bookingId}`);
        try {
            await snapshot.ref.update({ expiresAt: Date.now() + searchTimeoutMs });
        } catch (_) {}
        return null;

    } catch (error) {
        logger.error(`Error in onBookingCreated for booking ${bookingId}:`, error);
        // Update booking to "ERROR" if something goes wrong.
        return snapshot.ref.update({ status: "ERROR", cancellationReason: "SERVER_ERROR" });
    }
});

/**
 * V2 Callable Cloud Function to update the status of a trip.
 * This handles the progression of the trip from ACCEPTED to COMPLETED.
 */
exports.updateTripStatus = onCall(async (request) => {
    // Check authentication
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "You must be logged in.");
    }

    const driverId = request.auth.uid;
    const { bookingId, newStatus } = request.data;

    if (!bookingId || !newStatus) {
        throw new HttpsError("invalid-argument", "Missing 'bookingId' or 'newStatus'.");
    }

    const bookingRef = db.ref(`/bookingRequests/${bookingId}`);
    const bookingSnapshot = await bookingRef.once("value");
    const bookingData = bookingSnapshot.val();

    if (!bookingData) {
        throw new HttpsError("not-found", "Booking not found.");
    }

    // Security Check: Only the assigned driver can update the trip status.
    if (bookingData.driverId !== driverId) {
        throw new HttpsError("permission-denied", "You are not the driver for this trip.");
    }

    // State Machine Logic: Define valid transitions
    const validTransitions = {
        "ACCEPTED": ["EN_ROUTE_TO_PICKUP"],
        "EN_ROUTE_TO_PICKUP": ["ARRIVED_AT_PICKUP"],
        "ARRIVED_AT_PICKUP": ["EN_ROUTE_TO_DROPOFF"],
        "EN_ROUTE_TO_DROPOFF": ["AWAITING_PAYMENT"],
        "AWAITING_PAYMENT": ["COMPLETED"],
    };

    const allowedNextStates = validTransitions[bookingData.status];

    if (!allowedNextStates || !allowedNextStates.includes(newStatus)) {
        throw new HttpsError("failed-precondition", `Cannot transition from ${bookingData.status} to ${newStatus}.`);
    }

    // Update the booking status and timing/fare when appropriate.
    if (newStatus === "EN_ROUTE_TO_DROPOFF") {
        await bookingRef.update({ status: newStatus, tripStartedAt: Date.now() });
    } else if (newStatus === "AWAITING_PAYMENT") {
        const end = Date.now();
        const start = typeof bookingData.tripStartedAt === "number" ? bookingData.tripStartedAt : end;
        const durationMinutes = Math.max(0, Math.round((end - start) / 60000));
    
        const fareBase = typeof bookingData.fareBase === "number" ? bookingData.fareBase : 50;
        const perKmRate = typeof bookingData.perKmRate === "number" ? bookingData.perKmRate : 13.5;
        const perMinuteRate = typeof bookingData.perMinuteRate === "number" ? bookingData.perMinuteRate : 2;
    
        const pickup = { latitude: bookingData.pickupLatitude, longitude: bookingData.pickupLongitude };
        const dropoff = { latitude: bookingData.destinationLatitude, longitude: bookingData.destinationLongitude };
        const distanceKm = typeof bookingData.distanceKm === "number" ? bookingData.distanceKm : getDistance(pickup, dropoff);
        const discountPercent = typeof bookingData.appliedDiscountPercent === "number" ? bookingData.appliedDiscountPercent : 0;
        const subtotal = fareBase + perKmRate * (Number.isFinite(distanceKm) ? distanceKm : 0) + perMinuteRate * durationMinutes;
        const finalFare = discountPercent > 0 ? subtotal * (1 - discountPercent / 100) : subtotal;
    
        await bookingRef.update({ status: newStatus, tripEndedAt: end, durationMinutes, finalFare });
    } else if (newStatus === "COMPLETED") {
        // Ensure paymentConfirmed is recorded server-side to avoid client DB rule issues
        await bookingRef.update({ status: newStatus, paymentConfirmed: true });

        // Also archive directly to Firestore bookinghistory so history is guaranteed
        try {
            const afterSnapshot = await bookingRef.once("value");
            const afterData = afterSnapshot.val();
            if (afterData) {
                try {
                    const riderId = afterData.riderId;
                    if (riderId) {
                        await firestore.collection("users").doc(riderId)
                            .set({ nextBookingDiscountPercent: 0 }, { merge: true });
                        logger.log(`updateTripStatus: cleared nextBookingDiscountPercent for rider ${riderId}`);
                    }
                } catch (e) {
                    logger.warn("updateTripStatus: failed to clear rider discount", e);
                }
                const ts = typeof afterData.timestamp === "number" ? afterData.timestamp : (afterData.tripEndedAt || Date.now());
                const historyDoc = {
                    ...afterData,
                    status: "COMPLETED",
                    timestamp: ts,
                };
                await firestore.collection("bookinghistory").doc(bookingId).set(historyDoc, { merge: true });
                logger.log(`updateTripStatus: archived ${bookingId} to bookinghistory`);
            } else {
                logger.warn(`updateTripStatus: no data found to archive for ${bookingId}`);
            }
        } catch (err) {
            logger.error(`updateTripStatus: failed to archive ${bookingId}`, err);
        }
    } else {
        await bookingRef.update({ status: newStatus });
    }

    logger.log(`Trip ${bookingId} updated to ${newStatus} by driver ${driverId}.`);

    return { success: true, message: `Trip status updated to ${newStatus}.` };
});







/**
 * Calculates the Haversine distance between two points on the Earth.
 * @param {{latitude: number, longitude: number}} coord1 - The first coordinate.
 * @param {{latitude: number, longitude: number}} coord2 - The second coordinate.
 * @returns {number} The distance in kilometers.
 */

exports.aggregateDriverRating = onDocumentCreated("ratings/{ratingId}", async (event) => {
  try {
    const rating = event.data.data();
    const ratedId = rating?.ratedId;
    if (!ratedId) {
      logger.warn("aggregateDriverRating: missing ratedId");
      return;
    }

    const snap = await firestore.collection("ratings").where("ratedId", "==", ratedId).get();
    let sum = 0;
    let count = 0;
    snap.forEach((doc) => {
      const r = doc.get("rating");
      if (typeof r === "number") {
        sum += r;
        count++;
      }
    });
    const avg = count > 0 ? sum / count : 0;

    await firestore.collection("AVGrating").doc(`driver_rating_summaries_${ratedId}`).set(
      {
        ratedId,
        average: avg,
        count,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    logger.log(
      `aggregateDriverRating: updated summary for ${ratedId}: avg=${avg}, count=${count}`
    );
  } catch (err) {
    logger.error("aggregateDriverRating error", err);
  }
});

/**
 * Callable: Promote the current user to RTDB-readable admin via custom claims.
 * Requires that Firestore users/{uid}.role === 'Admin'.
 */
exports.promoteSelfToAdmin = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "You must be logged in.");
  }
  const uid = request.auth.uid;
  try {
    const doc = await firestore.collection("users").doc(uid).get();
    if (!doc.exists || doc.get("role") !== "Admin") {
      throw new HttpsError("permission-denied", "You are not an Admin.");
    }
    const user = await authAdmin.getUser(uid);
    const existing = user.customClaims || {};
    if (existing.admin === true) {
      return { ok: true, alreadyAdmin: true };
    }
    await authAdmin.setCustomUserClaims(uid, { ...existing, admin: true });
    return { ok: true, alreadyAdmin: false };
  } catch (err) {
    if (err instanceof HttpsError) throw err;
    throw new HttpsError("internal", err.message || "Failed to set admin claim");
  }
});

/**
 * Callable: Return booked driver IDs and booking counts.
 * Reads RTDB with admin privileges, so client does not need RTDB read access.
 */
exports.getBookedDrivers = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "You must be logged in.");
  }
  const uid = request.auth.uid;
  // Only Admins can call
  const doc = await firestore.collection("users").doc(uid).get();
  if (!doc.exists || doc.get("role") !== "Admin") {
    throw new HttpsError("permission-denied", "You are not an Admin.");
  }

  try {
    const snap = await rtdbAdmin.ref("bookingRequests").once("value");
    const data = snap.val() || {};
    const activeStates = new Set([
      "ACCEPTED",
      "EN_ROUTE_TO_PICKUP",
      "ARRIVED_AT_PICKUP",
      "EN_ROUTE_TO_DROPOFF",
      "AWAITING_PAYMENT",
    ]);
    const driverIds = new Set();
    let pendingCount = 0;
    let activeCount = 0;
    Object.keys(data).forEach((id) => {
      const b = data[id] || {};
      const st = String(b.status || "").toUpperCase();
      if (st === "SEARCHING") pendingCount++;
      if (activeStates.has(st)) {
        activeCount++;
        const dId = b.driverId || b.assignedDriverId || b.driverID;
        if (dId) driverIds.add(String(dId));
      }
    });
    return { driverIds: Array.from(driverIds), pendingCount, activeCount };
  } catch (err) {
    throw new HttpsError("internal", err.message || "Failed to read bookings");
  }
});

/**
 * Callable: Create a new driver account (Auth + Firestore) by an Admin.
 */
exports.createDriverAccount = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "You must be logged in.");
  }
  const adminUid = request.auth.uid;
  const adminDoc = await firestore.collection("users").doc(adminUid).get();
  if (!adminDoc.exists || adminDoc.get("role") !== "Admin") {
    throw new HttpsError("permission-denied", "You are not an Admin.");
  }

  const data = request.data || {};
  const name = String(data.name || '').trim();
  const email = String(data.email || '').trim();
  const password = String(data.password || '');
  const phone = data.phone ? String(data.phone) : null;
  const license = data.license ? String(data.license) : null;
  const address = data.address ? String(data.address) : null;
  const vehicle = data.vehicle || {};

  if (!name || !email || !password) {
    throw new HttpsError("invalid-argument", "Missing required fields: name, email, password.");
  }
  if (password.length < 6) {
    throw new HttpsError("invalid-argument", "Password must be at least 6 characters.");
  }

  try {
    const userRecord = await authAdmin.createUser({ email, password, displayName: name });
    const uid = userRecord.uid;

    const driverDoc = {
      uid,
      name,
      email,
      phone,
      license,
      address,
      role: 'Driver',
      status: 'active',
      vehicle: {
        make: vehicle.make || null,
        model: vehicle.model || null,
        year: vehicle.year || null,
        color: vehicle.color || null,
        licensePlate: vehicle.licensePlate || null,
      },
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    };
    await firestore.collection('drivers').doc(uid).set(driverDoc);

    // Do not mirror drivers into 'users' collection

    return { ok: true, driverId: uid };
  } catch (err) {
    if (err && (err.code === 'auth/email-already-exists')) {
      throw new HttpsError('already-exists', 'Email already exists.');
    }
    throw new HttpsError('internal', err?.message || 'Failed to create driver');
  }
});

/**
 * Callable: Update an existing driver account (Auth + Firestore) by an Admin.
 */
exports.updateDriverAccount = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "You must be logged in.");
  }
  const adminUid = request.auth.uid;
  const adminDoc = await firestore.collection("users").doc(adminUid).get();
  if (!adminDoc.exists || adminDoc.get("role") !== "Admin") {
    throw new HttpsError("permission-denied", "You are not an Admin.");
  }

  const data = request.data || {};
  const uid = String(data.driverId || "").trim();
  if (!uid) {
    throw new HttpsError("invalid-argument", "Missing driverId.");
  }

  const name = data.name !== undefined ? String(data.name).trim() : undefined;
  const email = data.email !== undefined ? String(data.email).trim() : undefined;
  const password = data.password !== undefined ? String(data.password) : undefined;
  const phone = data.phone !== undefined ? String(data.phone) : undefined;
  const license = data.license !== undefined ? String(data.license) : undefined;
  const address = data.address !== undefined ? String(data.address) : undefined;
  const status = data.status !== undefined ? String(data.status) : undefined;
  const vehicle = data.vehicle || {};

  try {
    const updates = {};
    if (email) updates.email = email;
    if (password && password.length >= 6) updates.password = password;
    if (name) updates.displayName = name;
    if (Object.keys(updates).length > 0) {
      await authAdmin.updateUser(uid, updates);
    }

    const driverDoc = {};
    if (name !== undefined) driverDoc.name = name;
    if (email !== undefined) driverDoc.email = email;
    if (phone !== undefined) driverDoc.phone = phone;
    if (license !== undefined) driverDoc.license = license;
    if (address !== undefined) driverDoc.address = address;
    if (status !== undefined) driverDoc.status = status;
    driverDoc.vehicle = {
      make: vehicle.make || null,
      model: vehicle.model || null,
      year: vehicle.year || null,
      color: vehicle.color || null,
      licensePlate: vehicle.licensePlate || null,
    };
    await firestore.collection("drivers").doc(uid).set(driverDoc, { merge: true });

    // Do not mirror drivers into 'users' collection

    return { ok: true };
  } catch (err) {
    throw new HttpsError("internal", err?.message || "Failed to update driver");
  }
});


/**
 * V2 Callable Cloud Function for a driver to accept a booking.
 *
 * This function is called by the driver'''s app. It uses a transaction
 * to ensure that only one driver can accept a booking.
 */
exports.acceptBooking = onCall(async (request) => {
    // Ensure the user is an authenticated driver.
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "You must be logged in to accept a booking.");
    }

    const driverId = request.auth.uid;
    const { bookingId } = request.data; // Destructure bookingId from request data

    if (!bookingId) {
        throw new HttpsError("invalid-argument", "The function must be called with a 'bookingId'.");
    }

    const bookingRef = db.ref(`/bookingRequests/${bookingId}`);

    try {
        // Use a transaction to prevent race conditions (multiple drivers accepting the same ride).
        const transactionResult = await bookingRef.transaction((currentData) => {
            if (currentData === null) {
                return null; // The booking was deleted.
            }
            // If the status is "SEARCHING", we can accept it.
            if (currentData.status === "SEARCHING") {
                // Update status and assign driverId
                currentData.status = "ACCEPTED";
                currentData.driverId = driverId;
                return currentData;
            }
            // Otherwise, another driver has already taken it or it was canceled.
            return; // Abort the transaction by returning undefined.
        });

        // Check if the transaction was successful.
        if (!transactionResult.committed) {
            logger.log(`Driver ${driverId} failed to accept booking ${bookingId} because it was already taken.`);
            throw new HttpsError("aborted", "This booking is no longer available.");
        }

        // Get accepting driver details from Firestore 'drivers' (admin-managed).
        let driverName = "Your Driver";
        let driverPhone = null;
        let driverVehicleDetails = "Standard Car";
        try {
            const driverDoc = await firestore.collection("drivers").doc(driverId).get();
            if (driverDoc.exists) {
                driverName = driverDoc.get("name") || driverName;
                driverPhone = driverDoc.get("phone") || driverPhone;
                const vehicle = driverDoc.get("vehicle") || {};
                const make = vehicle.make || "";
                const model = vehicle.model || "";
                const year = vehicle.year || "";
                const color = vehicle.color || "";
                const licensePlate = vehicle.licensePlate || "";
                // Compose a readable vehicle summary from admin-configured fields
                const parts = [year, make, model].filter(Boolean).join(" ");
                driverVehicleDetails = [parts, color, licensePlate].filter(Boolean).join(", ");
            } else {
                logger.warn(`acceptBooking: Firestore drivers/${driverId} not found; using fallbacks.`);
            }
        } catch (e) {
            logger.error(`acceptBooking: Failed to fetch driver doc for ${driverId}`, e);
        }

        // Update the booking with the admin-managed driver details.
        await bookingRef.update({
            driverName,
            driverVehicleDetails,
            driverPhone,
        });

        logger.log(`Booking ${bookingId} accepted by driver ${driverId}.`);

        // After successfully accepting, clean up ALL offers for this booking.
        await cleanupOffersForBooking(bookingId);

        return { success: true, message: "Booking accepted successfully." };

    } catch (error) {
        logger.error(`Error accepting booking ${bookingId} for driver ${driverId}:`, error);
        // Re-throw the error to be handled by the client.
        if (error instanceof HttpsError) {
            throw error;
        }
        throw new HttpsError("internal", "An unexpected error occurred while accepting the booking.");
    }
});


/**
 * V2 Callable Cloud Function to cancel a booking.
 *
 * This can be called by the rider.
 */
exports.cancelBooking = onCall(async (request) => {
    // Check authentication.
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "You must be logged in to cancel a booking.");
    }

    const { bookingId } = request.data;
    if (!bookingId) {
        throw new HttpsError("invalid-argument", "The function must be called with a 'bookingId'.");
    }

    const bookingRef = db.ref(`/bookingRequests/${bookingId}`);
    const bookingSnapshot = await bookingRef.once("value");
    const bookingData = bookingSnapshot.val();

    if (!bookingData) {
        throw new HttpsError("not-found", "The specified booking does not exist.");
    }

    const callerId = request.auth.uid;
    const forbidden = new Set(["EN_ROUTE_TO_DROPOFF", "AWAITING_PAYMENT", "COMPLETED"]);
    if (forbidden.has(String(bookingData.status || "").toUpperCase())) {
        throw new HttpsError("failed-precondition", "Trip has started to drop off.");
    }

    let reason = "user_canceled";
    if (bookingData.riderId === callerId) {
        reason = "rider_canceled";
    } else if (bookingData.driverId === callerId) {
        reason = "driver_canceled";
    } else {
        throw new HttpsError("permission-denied", "You are not authorized to cancel this booking.");
    }

    await bookingRef.update({
        status: "CANCELED",
        cancellationReason: reason,
    });

    logger.log(`Booking ${bookingId} canceled by user ${request.auth.uid}.`);

    // Clean up offers from the driverOffers path.
    await cleanupOffersForBooking(bookingId);

    return { success: true, message: "Booking canceled." };
});

exports.expireStaleSearches = onSchedule("every 1 minutes", async (event) => {
    const snapshot = await db.ref("bookingRequests").orderByChild("status").equalTo("SEARCHING").once("value");
    if (!snapshot.exists()) return null;
    const now = Date.now();
    const updates = [];
    snapshot.forEach((child) => {
        const data = child.val() || {};
        const exp = data.expiresAt;
        if (typeof exp === "number" && exp <= now) {
            updates.push(child.ref.update({ status: "CANCELED", cancellationReason: "timeout_no_driver" }));
            updates.push(cleanupOffersForBooking(child.key));
        }
    });
    if (updates.length > 0) await Promise.all(updates);
    return null;
});


/**
 * Helper function to remove a booking offer from all drivers''' offer lists.
 * This is crucial to prevent drivers from seeing or accepting a ride that'''s
 * already been taken or canceled.
 */
async function cleanupOffersForBooking(bookingId) {
    const offersRef = db.ref("/driverOffers");
    const snapshot = await offersRef.once("value");

    if (!snapshot.exists()) {
        return; // No offers to clean up
    }

    const cleanupPromises = [];
    snapshot.forEach((driverOffersSnapshot) => {
        const driverId = driverOffersSnapshot.key;
        if (driverOffersSnapshot.hasChild(bookingId)) {
            const offerRef = db.ref(`/driverOffers/${driverId}/${bookingId}`);
            cleanupPromises.push(offerRef.remove());
        }
    });

    if (cleanupPromises.length > 0) {
        await Promise.all(cleanupPromises);
        logger.log(`Cleaned up all offers for booking ${bookingId}.`);
    }
}

function getDistance(coord1, coord2) {
    if (!coord1 || !coord2) return Infinity;
    const R = 6371;
    const dLat = (coord2.latitude - coord1.latitude) * Math.PI / 180;
    const dLon = (coord2.longitude - coord1.longitude) * Math.PI / 180;
    const a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(coord1.latitude * Math.PI / 180) * Math.cos(coord2.latitude * Math.PI / 180) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return R * c;
}
