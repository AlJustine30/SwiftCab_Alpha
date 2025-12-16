package com.btsi.swiftcab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RatingBar
import android.widget.TextView
import android.content.Intent
import androidx.recyclerview.widget.RecyclerView
import com.btsi.swiftcab.models.BookingRequest
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.firestore.FirebaseFirestore

class DriverOffersAdapter(
    private val offers: MutableList<BookingRequest>,
    private var driverLocation: LatLng?,
    private val onAccept: (BookingRequest) -> Unit,
    private val onDecline: (BookingRequest) -> Unit
) : RecyclerView.Adapter<DriverOffersAdapter.OfferViewHolder>() {

    /**
     * Updates the driver’s current location and refreshes items for distance display.
     */
    fun updateDriverLocation(loc: LatLng?) {
        driverLocation = loc
        notifyItemRangeChanged(0, offers.size)
    }

    /**
     * Replaces the offer list and refreshes the adapter.
     */
    fun setOffers(newOffers: List<BookingRequest>) {
        offers.clear()
        offers.addAll(newOffers)
        notifyDataSetChanged()
    }

    /**
     * Inflates an offer item and creates its ViewHolder.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OfferViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_driver_offer, parent, false)
        return OfferViewHolder(view)
    }

    /**
     * Binds an offer to the ViewHolder including passenger, route, and distance.
     */
    override fun onBindViewHolder(holder: OfferViewHolder, position: Int) {
        val offer = offers[position]
        holder.bind(offer, driverLocation, onAccept, onDecline)
    }

    /**
     * Returns the number of available offers.
     */
    override fun getItemCount(): Int = offers.size

    class OfferViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textPassenger: TextView = itemView.findViewById(R.id.textViewOfferPassenger)
        private val textPickup: TextView = itemView.findViewById(R.id.textViewOfferPickup)
        private val textDestination: TextView = itemView.findViewById(R.id.textViewOfferDestination)
        private val textDistance: TextView = itemView.findViewById(R.id.textViewOfferDistance)
        private val textEstimatedFare: TextView = itemView.findViewById(R.id.textViewOfferEstimatedFare)
        private val btnAccept: Button = itemView.findViewById(R.id.buttonOfferAccept)
        private val btnDecline: Button = itemView.findViewById(R.id.buttonOfferDecline)
        private val ratingBar: RatingBar = itemView.findViewById(R.id.ratingBarOfferRider)
        private val ratingCountText: TextView = itemView.findViewById(R.id.textViewOfferRiderRatingCount)
        private val btnViewReviews: Button = itemView.findViewById(R.id.buttonOfferViewReviews)
        private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

        /**
         * Binds offer information and actions to views, including distance to pickup.
         *
         * @param offer the rider booking offer
         * @param driverLoc current driver location for distance calculation
         * @param onAccept callback when the driver accepts
         * @param onDecline callback when the driver declines
         */
        fun bind(
            offer: BookingRequest,
            driverLoc: LatLng?,
            onAccept: (BookingRequest) -> Unit,
            onDecline: (BookingRequest) -> Unit
        ) {
            textPassenger.text = "Passenger: ${offer.riderName ?: "Unknown"}"
            textPickup.text = "Pickup: ${offer.pickupAddress ?: "Unknown"}"
            textDestination.text = "Destination: ${offer.destinationAddress ?: "Unknown"}"

            val pickupLat = offer.pickupLatitude ?: 0.0
            val pickupLng = offer.pickupLongitude ?: 0.0
            val pickup = LatLng(pickupLat, pickupLng)

            if (driverLoc != null) {
                val km = calculateDistanceKm(driverLoc, pickup)
                textDistance.visibility = View.VISIBLE
                textDistance.text = String.format("Distance to pickup: %.2f km", km)
            } else {
                textDistance.visibility = View.VISIBLE
                textDistance.text = "Distance to pickup: -- km"
            }

            val base = offer.fareBase ?: 50.0
            val perKm = offer.perKmRate ?: 13.5
            val estFare = offer.estimatedFare ?: run {
                val destLat = offer.destinationLatitude ?: 0.0
                val destLng = offer.destinationLongitude ?: 0.0
                val dest = LatLng(destLat, destLng)
                val tripKm = calculateDistanceKm(pickup, dest)
                base + (perKm * tripKm)
            }
            textEstimatedFare.text = String.format(java.util.Locale.getDefault(), "Estimated Fare: ₱%.2f", estFare)
            textEstimatedFare.visibility = View.VISIBLE

            btnAccept.setOnClickListener { onAccept(offer) }
            btnDecline.setOnClickListener { onDecline(offer) }

            btnViewReviews.setOnClickListener {
                val ctx = itemView.context
                val intent = Intent(ctx, DriverRatingsActivity::class.java)
                intent.putExtra("TARGET_USER_ID", offer.riderId)
                intent.putExtra("FILTER_DRIVERS_ONLY", true)
                ctx.startActivity(intent)
            }

            val riderId = offer.riderId
            if (riderId.isNullOrBlank()) {
                ratingBar.rating = 0f
                ratingCountText.text = "(0)"
                btnViewReviews.isEnabled = false
            } else {
                btnViewReviews.isEnabled = true
                firestore.collection("AVGrating").document("driver_rating_summaries_$riderId")
                    .get()
                    .addOnSuccessListener { doc ->
                        val avg = doc.getDouble("average") ?: 0.0
                        val count = doc.getLong("count")?.toInt() ?: 0
                        ratingBar.rating = avg.toFloat()
                        ratingCountText.text = String.format(java.util.Locale.getDefault(), "(%d)", count)
                    }
                    .addOnFailureListener {
                        ratingBar.rating = 0f
                        ratingCountText.text = "(0)"
                    }
            }
        }

        /**
         * Computes distance between two points using `Location.distanceBetween`.
         *
         * @return distance in kilometers
         */
        private fun calculateDistanceKm(a: LatLng, b: LatLng): Double {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
            return results[0] / 1000.0
        }
    }
}
