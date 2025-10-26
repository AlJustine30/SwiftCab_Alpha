package com.btsi.swiftcab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.btsi.swiftcab.models.BookingRequest
import com.google.android.gms.maps.model.LatLng

class DriverOffersAdapter(
    private val offers: MutableList<BookingRequest>,
    private var driverLocation: LatLng?,
    private val onAccept: (BookingRequest) -> Unit,
    private val onDecline: (BookingRequest) -> Unit
) : RecyclerView.Adapter<DriverOffersAdapter.OfferViewHolder>() {

    fun updateDriverLocation(loc: LatLng?) {
        driverLocation = loc
        notifyItemRangeChanged(0, offers.size)
    }

    fun setOffers(newOffers: List<BookingRequest>) {
        offers.clear()
        offers.addAll(newOffers)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OfferViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_driver_offer, parent, false)
        return OfferViewHolder(view)
    }

    override fun onBindViewHolder(holder: OfferViewHolder, position: Int) {
        val offer = offers[position]
        holder.bind(offer, driverLocation, onAccept, onDecline)
    }

    override fun getItemCount(): Int = offers.size

    class OfferViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textPassenger: TextView = itemView.findViewById(R.id.textViewOfferPassenger)
        private val textPickup: TextView = itemView.findViewById(R.id.textViewOfferPickup)
        private val textDestination: TextView = itemView.findViewById(R.id.textViewOfferDestination)
        private val textDistance: TextView = itemView.findViewById(R.id.textViewOfferDistance)
        private val btnAccept: Button = itemView.findViewById(R.id.buttonOfferAccept)
        private val btnDecline: Button = itemView.findViewById(R.id.buttonOfferDecline)

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

            btnAccept.setOnClickListener { onAccept(offer) }
            btnDecline.setOnClickListener { onDecline(offer) }
        }

        private fun calculateDistanceKm(a: LatLng, b: LatLng): Double {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
            return results[0] / 1000.0
        }
    }
}
