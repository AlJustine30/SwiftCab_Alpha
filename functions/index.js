/* eslint-disable max-len */

const {onValueCreated} = require("firebase-functions/v2/database");
const {logger} = require("firebase-functions/v2");
const admin = require("firebase-admin");

if (!admin.apps.length) {
  admin.initializeApp();
}

const DEMO_DRIVER_ID = "mHRfUFiY3xXXKZkskTmFxRxQrAb2"; // UPDATED to your driver's UID

exports.processNewBooking = onValueCreated(
    {
      ref: "/bookingRequests/{bookingId}",
      instance: "swiftcab-de564-default-rtdb",
    },
    async (event) => {
      const bookingId = event.params.bookingId;
      const bookingData = event.data.val();

      logger.info(`V2: New booking received. Booking ID: ${bookingId}`, bookingData);

      if (!bookingData || bookingData.status !== "PENDING") {
        logger.info(
            `V2: Booking ${bookingId} not valid or not PENDING (status: ${bookingData ? bookingData.status : "N/A"
            })`,
        );
        return null;
      }

      logger.info(`V2: Processing PENDING booking: ${bookingId}`);
      await new Promise((resolve) => setTimeout(resolve, 7000)); // Simulate delay

      const simulateDriverFound = Math.random() > 0.3;
      let finalUserBookingStatus = {};
      let mockDriverDetails = {};

      if (simulateDriverFound) {
        mockDriverDetails = {
          driverId: DEMO_DRIVER_ID,
          name: "Demo Driver (UID: " + DEMO_DRIVER_ID.slice(-5) + ")", // Updated name
          carModel: "Demo Vehicle",
          carPlate: "DEMO 123",
          currentLat: bookingData.pickupLat ? (bookingData.pickupLat + 0.005) : 0,
          currentLng: bookingData.pickupLng ? (bookingData.pickupLng + 0.005) : 0,
          eta: "5 mins",
        };

        finalUserBookingStatus = {
          status: "DRIVER_ASSIGNED",
          driverDetails: mockDriverDetails,
          assignedAt: admin.database.ServerValue.TIMESTAMP,
        };
        logger.info(
            `V2: Demo Driver ${mockDriverDetails.name} selected for ${bookingId}.`,
        );

        const driverRideOfferData = {
          bookingId: bookingId,
          userId: bookingData.userId,
          userName: bookingData.userName || "Valued Customer",
          pickupAddress: bookingData.pickupAddress || "Unknown Pickup",
          pickupLat: bookingData.pickupLat,
          pickupLng: bookingData.pickupLng,
          destinationAddress: bookingData.destinationAddress || "Unknown Dest.",
          destinationLat: bookingData.destinationLat,
          destinationLng: bookingData.destinationLng,
          fareEstimate: bookingData.fareEstimate || "N/A",
          status: "NEW_RIDE_OFFER",
          offerSentAt: admin.database.ServerValue.TIMESTAMP,
        };

        try {
          await admin.database()
              .ref(`/driverRideOffers/${DEMO_DRIVER_ID}/${bookingId}`)
              .set(driverRideOfferData);
          logger.info(
              `V2: Ride offer to DEMO_DRIVER_ID ${DEMO_DRIVER_ID} for ${bookingId} sent.`,
          );
        } catch (error) {
          logger.error(
              `V2: Failed to send ride offer to ${DEMO_DRIVER_ID}:`, error,
          );
        }
      } else {
        finalUserBookingStatus = {
          status: "NO_DRIVERS_FOUND",
          processedAt: admin.database.ServerValue.TIMESTAMP,
        };
        logger.info(`V2: No drivers found for booking ${bookingId} (simulated).`);
      }

      try {
        await admin.database()
            .ref(`/bookingRequests/${bookingId}`)
            .update(finalUserBookingStatus);
        logger.info(
            `V2: User booking ${bookingId} updated: ${finalUserBookingStatus.status}`,
        );
      } catch (error) {
        logger.error(
            `V2: Failed to update user booking ${bookingId}:`, error,
        );
      }
      return null;
    },
);

