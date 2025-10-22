
// V2 Imports
const { onValueCreated } = require("firebase-functions/v2/database");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { logger } = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.database();
const firestore = admin.firestore();

/**
 * V2 Cloud Function triggered when a booking'''s status changes.
 * If the status becomes "COMPLETED", it archives the booking to Firestore and deletes it from Realtime DB.
 */
exports.archiveBooking = onValueCreated("/bookingRequests/{bookingId}", async (event) => {
    const bookingSnapshot = event.data;
    const bookingData = bookingSnapshot.val();
    const bookingId = event.params.bookingId;

    if (bookingData.status === "COMPLETED") {
        logger.log(`Archiving booking ${bookingId}`);

        try {
            // 1. Copy the data to Firestore
            await firestore.collection("completedBookings").doc(bookingId).set(bookingData);

            // 2. Delete the original from Realtime Database
            await bookingSnapshot.ref.remove();

            logger.log(`Successfully archived booking ${bookingId} to Firestore.`);
            return null;
        } catch (error) {
            logger.error(`Error archiving booking ${bookingId}:`, error);
            return null;
        }
    }
    return null;
});


/**
 * V2 Cloud Function triggered when a new booking request is created.
 *
 * This function finds the 5 nearest online drivers and sends them a booking offer.
 */
exports.onBookingCreated = onValueCreated("/bookingRequests/{bookingId}", async (event) => {
    // The snapshot is available at event.data
    const snapshot = event.data;
    const bookingData = snapshot.val();
    const bookingId = event.params.bookingId;

    // Ensure the function only runs for new bookings in the "SEARCHING" state.
    if (bookingData.status !== "SEARCHING") {
        logger.log(`Booking ${bookingId} is not in SEARCHING state, ignoring.`);
        return null;
    }

    logger.log(`New booking ${bookingId}:`, bookingData);

    try {
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

        // Sort drivers by distance and take the closest 5.
        driversWithDistance.sort((a, b) => a.distance - b.distance);
        const closestDrivers = driversWithDistance.slice(0, 5);

        if (closestDrivers.length === 0) {
            logger.warn("No drivers with location data found.");
            return snapshot.ref.update({ status: "NO_DRIVERS", cancellationReason: "NO_DRIVERS_NEARBY" });
        }

        logger.log(`Found ${closestDrivers.length} closest drivers.`);

        // Create an offer for each of the closest drivers.
        const offerPromises = closestDrivers.map((driverInfo) => {
            const driverId = driverInfo.driverId;
            // The offer includes the complete booking data plus a server timestamp.
            return db.ref(`/driverOffers/${driverId}/${bookingId}`).set({
                ...bookingData,
                timestamp: admin.database.ServerValue.TIMESTAMP,
            });
        });

        await Promise.all(offerPromises);
        logger.log(`Offers sent to drivers for booking ${bookingId}`);
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
        "EN_ROUTE_TO_DROPOFF": ["COMPLETED"],
    };

    const allowedNextStates = validTransitions[bookingData.status];

    if (!allowedNextStates || !allowedNextStates.includes(newStatus)) {
        throw new HttpsError("failed-precondition", `Cannot transition from ${bookingData.status} to ${newStatus}.`);
    }

    // Update the booking status.
    await bookingRef.update({ status: newStatus });

    logger.log(`Trip ${bookingId} updated to ${newStatus} by driver ${driverId}.`);

    return { success: true, message: `Trip status updated to ${newStatus}.` };
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

    // Verify that the user calling the function is the rider who created the booking.
    if (bookingData.riderId !== request.auth.uid) {
        throw new HttpsError("permission-denied", "You are not authorized to cancel this booking.");
    }

    // Update the status to CANCELED.
    await bookingRef.update({
        status: "CANCELED",
        cancellationReason: "user_canceled", // Specific reason for client-side logic
    });

    logger.log(`Booking ${bookingId} canceled by user ${request.auth.uid}.`);

    // Clean up offers from the driverOffers path.
    await cleanupOffersForBooking(bookingId);

    return { success: true, message: "Booking canceled." };
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

/**
 * Calculates the Haversine distance between two points on the Earth.
 * @param {{latitude: number, longitude: number}} coord1 - The first coordinate.
 * @param {{latitude: number, longitude: number}} coord2 - The second coordinate.
 * @returns {number} The distance in kilometers.
 */
function getDistance(coord1, coord2) {
    if (!coord1 || !coord2) return Infinity;
    const R = 6371; // Radius of the Earth in km
    const dLat = (coord2.latitude - coord1.latitude) * Math.PI / 180;
    const dLon = (coord2.longitude - coord1.longitude) * Math.PI / 180;
    const a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(coord1.latitude * Math.PI / 180) * Math.cos(coord2.latitude * Math.PI / 180) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return R * c; // Distance in km
}

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

    await firestore.collection("public").doc(`driver_rating_summaries_${ratedId}`).set(
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

    // Verify that the user calling the function is the rider who created the booking.
    if (bookingData.riderId !== request.auth.uid) {
        throw new HttpsError("permission-denied", "You are not authorized to cancel this booking.");
    }

    // Update the status to CANCELED.
    await bookingRef.update({
        status: "CANCELED",
        cancellationReason: "user_canceled", // Specific reason for client-side logic
    });

    logger.log(`Booking ${bookingId} canceled by user ${request.auth.uid}.`);

    // Clean up offers from the driverOffers path.
    await cleanupOffersForBooking(bookingId);

    return { success: true, message: "Booking canceled." };
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

/**
 * Calculates the Haversine distance between two points on the Earth.
 * @param {{latitude: number, longitude: number}} coord1 - The first coordinate.
 * @param {{latitude: number, longitude: number}} coord2 - The second coordinate.
 * @returns {number} The distance in kilometers.
 */
function getDistance(coord1, coord2) {
    if (!coord1 || !coord2) return Infinity;
    const R = 6371; // Radius of the Earth in km
    const dLat = (coord2.latitude - coord1.latitude) * Math.PI / 180;
    const dLon = (coord2.longitude - coord1.longitude) * Math.PI / 180;
    const a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(coord1.latitude * Math.PI / 180) * Math.cos(coord2.latitude * Math.PI / 180) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return R * c; // Distance in km
}


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

    await firestore.collection("public").doc(`driver_rating_summaries_${ratedId}`).set(
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