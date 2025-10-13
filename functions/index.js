/* eslint-disable max-len */

const {onValueCreated, onValueUpdated} = require("firebase-functions/v2/database");
const {logger} = require("firebase-functions/v2");
const admin = require("firebase-admin");
const {onCall, HttpsError} = require("firebase-functions/v2/https");

// Initialize Admin SDK if not already done
if (!admin.apps.length) {
  admin.initializeApp();
}

// --- CONFIGURATION ---

// The maximum distance (in degrees latitude/longitude) to search for drivers.
// 0.1 degrees is roughly 11km. This is the critical value that was wrong before.
const SEARCH_RADIUS_DEGREES = 0.1;

// How long (in milliseconds) to wait for a driver to accept before a booking is considered stale.
const BOOKING_TIMEOUT_MS = 45000;


// --- USER FACING FUNCTIONS (MUST BE FAST) ---

/**
 * A callable function for a driver to accept a booking.
 * Re-architected to be lightning-fast and self-healing. It handles claiming the booking
 * AND timing out stale bookings, eliminating the need for a separate timer.
 */
exports.acceptBooking = onCall(async (request) => {
  if (!request.auth || !request.auth.token.driver) {
    throw new HttpsError("unauthenticated", "You must be a logged-in driver to accept bookings.");
  }
  const driverId = request.auth.uid;
  const {bookingId} = request.data;

  if (!bookingId) {
    throw new HttpsError("invalid-argument", "The function must be called with a 'bookingId'.");
  }

  const bookingRef = admin.database().ref(`/booking_requests/${bookingId}`);

  try {
    const {committed, snapshot} = await bookingRef.transaction((currentData) => {
      // If the booking doesn't exist, abort.
      if (currentData === null) {
        return; // Abort.
      }

      // SELF-HEALING: Check if the booking has expired.
      const bookingAge = Date.now() - currentData.createdAt;
      if (currentData.status === "PENDING" && bookingAge > BOOKING_TIMEOUT_MS) {
        // This transaction will now mark the booking as timed out.
        currentData.status = "NO_DRIVERS_FOUND";
        return currentData;
      }

      // If booking is not in a state that can be accepted, abort.
      if (currentData.status !== "PENDING") {
        return; // Abort.
      }

      // If we are here, the booking is valid and pending. Claim it.
      currentData.status = "ACCEPTED";
      currentData.driverId = driverId;
      return currentData;
    });
    
    const finalData = snapshot.val();

    if (committed) {
      if (finalData.status === "ACCEPTED" && finalData.driverId === driverId) {
        // SUCCESS: We successfully claimed the booking.
        logger.info(`Driver ${driverId} has successfully claimed booking ${bookingId}.`);
        return {status: "success", message: "Booking accepted successfully!"};
      } else if (finalData.status === "NO_DRIVERS_FOUND") {
        // We were the one to time out the booking. Inform the driver.
        logger.warn(`Driver ${driverId} attempted to accept expired booking ${bookingId}.`);
        throw new HttpsError("aborted", "This booking has expired.");
      }
    }

    // If the transaction did not commit, it means another process changed the data.
    // We provide a clear reason why the acceptance failed.
    if (!finalData) {
      throw new HttpsError("not-found", "This booking no longer exists.");
    }
    
    // Idempotency: If we find out we already own it, it's a success.
    if (finalData.driverId === driverId) {
      logger.info(`Idempotent success for booking ${bookingId} by driver ${driverId}.`);
      return {status: "success", message: "Booking already accepted by you."};
    }
    
    // Contention: If it's STILL pending, the line was just busy.
    if (finalData.status === "PENDING") {
      logger.warn(`Transaction for ${bookingId} failed due to contention.`);
      throw new HttpsError("aborted", "The line is busy, please try again.");
    }

    // Final case: Someone else got it, or it was cancelled.
    throw new HttpsError("aborted", "This booking is no longer available.");
  } catch (error) {
    logger.error(`Critical error in acceptBooking for ${bookingId}:`, error);
    if (error instanceof HttpsError) {
      throw error;
    }
    throw new HttpsError("internal", "An unexpected error occurred while accepting the booking.");
  }
});


/**
 * A callable function for a passenger to cancel their pending booking.
 */
exports.cancelBooking = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "You must be logged in to cancel a booking.");
  }
  const riderId = request.auth.uid;
  const {bookingId} = request.data;

  const bookingRef = admin.database().ref(`/booking_requests/${bookingId}`);
  const bookingSnap = await bookingRef.once("value");
  const bookingData = bookingSnap.val();

  if (!bookingSnap.exists()) {
    throw new HttpsError("not-found", "The specified booking does not exist.");
  }
  if (bookingData.riderId !== riderId) {
    throw new HttpsError("permission-denied", "You are not authorized to cancel this booking.");
  }
  if (bookingData.status !== "PENDING") {
    throw new HttpsError("failed-precondition", `You cannot cancel a booking with status: ${bookingData.status}.`);
  }

  await bookingRef.update({status: "CANCELLED_BY_PASSENGER"});
  logger.info(`Rider ${riderId} successfully cancelled booking ${bookingId}.`);
  return {status: "success", message: "Booking cancelled successfully."};
});


// --- BACKGROUND WORKER FUNCTIONS ---

/**
 * Main background worker that handles all slow tasks after a booking's status changes.
 */
exports.onBookingFinalized = onValueUpdated( "/booking_requests/{bookingId}", async (event) => {
  const bookingId = event.params.bookingId;
  const dataAfter = event.data.after.val();
  const dataBefore = event.data.before.val();
  
  if (dataAfter.status === dataBefore.status) {
    return null; // Status has not changed.
  }
  
  logger.info(`Booking ${bookingId} status changed to ${dataAfter.status}. Performing background tasks.`);
  
  // Task 1: If booking is accepted, enrich it with driver data AND notify passenger.
  if (dataAfter.status === "ACCEPTED" && dataAfter.driverId) {
    try {
      const driverDoc = await admin.firestore().collection("drivers").doc(dataAfter.driverId).get();
      const driverData = driverDoc.exists ? driverDoc.data() : {};
      
      const driverName = driverData.name || "Your Driver";
      const driverVehicle = driverData.vehicleDetails || "Unknown Vehicle";

      await event.data.after.ref.update({
        driverName: driverName,
        driverVehicleDetails: driverVehicle,
      });
      logger.info(`Enriched booking ${bookingId} with driver details.`);

      // --- NEW FEATURE: NOTIFY PASSENGER ---
      if (dataAfter.riderId) {
        // Assumes you store user data, including FCM token, in a 'users' collection in Firestore.
        const userDoc = await admin.firestore().collection("users").doc(dataAfter.riderId).get();
        if (userDoc.exists() && userDoc.data().fcmToken) {
          const fcmToken = userDoc.data().fcmToken;
          const message = {
            notification: {
              title: "Your driver is on the way!",
              body: `${driverName} in a ${driverVehicle} has accepted your ride.`,
            },
            token: fcmToken,
            data: { bookingId: bookingId, status: "ACCEPTED" },
          };
          await admin.messaging().send(message);
          logger.info(`Successfully sent acceptance notification to rider ${dataAfter.riderId}`);
        }
      }
    } catch (error) {
      logger.error(`Error during post-acceptance tasks for booking ${bookingId}:`, error);
    }
  }
  
  // Task 2: If booking is finalized, clean up all offers.
  const terminalStates = ["ACCEPTED", "CANCELLED_BY_PASSENGER", "NO_DRIVERS_FOUND"];
  if (terminalStates.includes(dataAfter.status)) {
    await cleanupRideOffers(bookingId);
  }
  
  return null;
});

/**
 * Helper to efficiently delete ride offers using a single, atomic, multi-path update.
 */
async function cleanupRideOffers(bookingId) {
  logger.info(`Cleaning up offers for finalized booking ${bookingId}...`);
  const offersMetaRef = admin.database().ref(`/bookingOffers/${bookingId}`);
  const offersMetaSnap = await offersMetaRef.once("value");

  if (!offersMetaSnap.exists()) {
    logger.warn(`No offer metadata for booking ${bookingId} to clean up.`);
    return;
  }

  const driverIds = offersMetaSnap.val().driverIds;
  if (!driverIds || !Array.isArray(driverIds)) {
    await offersMetaRef.remove(); // Clean up incomplete metadata.
    return;
  }

  const fanoutObject = {};
  driverIds.forEach((driverId) => {
    fanoutObject[`/driverRideOffers/${driverId}/${bookingId}`] = null;
  });
  fanoutObject[`/bookingOffers/${bookingId}`] = null; // Also delete the metadata.

  try {
    await admin.database().ref().update(fanoutObject);
    logger.info(`Successfully cleaned ${driverIds.length} offers for booking ${bookingId}.`);
  } catch (error) {
    logger.error(`Error during multi-path cleanup for ${bookingId}:`, error);
  }
}

/**
 * Processes a new booking, finds nearby drivers, and fans out offers.
 * REMOVED setTimeout to prevent race conditions.
 */
exports.processNewBooking = onValueCreated("/booking_requests/{bookingId}", async (event) => {
  const bookingId = event.params.bookingId;
  const bookingData = event.data.val();

  if (!bookingData || bookingData.status !== "PENDING") {
    return null;
  }
  
  // CRITICAL: Add a creation timestamp for timeout logic.
  await event.data.ref.update({createdAt: admin.database.ServerValue.TIMESTAMP});

  logger.info(`Processing new booking: ${bookingId}`);

  const onlineDriversSnap = await admin.database().ref("/online_drivers").once("value");
  if (!onlineDriversSnap.exists()) {
    await admin.database().ref(`/booking_requests/${bookingId}`).update({status: "NO_DRIVERS_FOUND"});
    return null;
  }

  const onlineDrivers = onlineDriversSnap.val();
  const nearbyDriverIds = [];

  for (const driverId in onlineDrivers) {
    const driver = onlineDrivers[driverId];
    if (driver.lat && driver.lng) {
      if (
        Math.abs(driver.lat - bookingData.pickupLatitude) <= SEARCH_RADIUS_DEGREES &&
        Math.abs(driver.lng - bookingData.pickupLongitude) <= SEARCH_RADIUS_DEGREES
      ) {
        nearbyDriverIds.push(driverId);
      }
    }
  }

  if (nearbyDriverIds.length === 0) {
    await admin.database().ref(`/booking_requests/${bookingId}`).update({status: "NO_DRIVERS_FOUND"});
    return null;
  }

  logger.info(`Found ${nearbyDriverIds.length} nearby drivers for ${bookingId}.`);

  await admin.database().ref(`/bookingOffers/${bookingId}`).set({driverIds: nearbyDriverIds});

  const offers = nearbyDriverIds.map((driverId) => {
    return admin.database().ref(`/driverRideOffers/${driverId}/${bookingId}`).set({
      ...bookingData, // Spread existing booking data
      bookingId: bookingId,
    });
  });
  
  await Promise.all(offers);
  return null;
});


// --- UTILITY AND AUTH FUNCTIONS ---

exports.grantDriverRole = onCall(async (request) => {
  if (!request.auth || !request.auth.token.admin) { // Recommended: only admins grant roles
    throw new HttpsError("unauthenticated", "You must be an admin to grant roles.");
  }
  const email = request.data.email;
  if (!email) {
    throw new HttpsError("invalid-argument", "Missing 'email' argument.");
  }
  try {
    const user = await admin.auth().getUserByEmail(email);
    await admin.auth().setCustomUserClaims(user.uid, {driver: true});
    logger.info(`Successfully granted driver role to ${email}`);
    return {message: `Success! ${email} has been made a driver.`};
  } catch (error) {
    logger.error(`Error granting driver role to ${email}:`, error);
    throw new HttpsError("internal", "Error setting custom claims.");
  }
});
