package com.btsi.swiftcab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RatingBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.btsi.swiftcab.models.Rating
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RatingsAdapter : ListAdapter<Rating, RatingsAdapter.RatingViewHolder>(DIFF) {
    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Rating>() {
            override fun areItemsTheSame(oldItem: Rating, newItem: Rating): Boolean =
                oldItem.bookingId == newItem.bookingId && oldItem.timestamp == newItem.timestamp
            override fun areContentsTheSame(oldItem: Rating, newItem: Rating): Boolean =
                oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RatingViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_rating, parent, false)
        return RatingViewHolder(view)
    }

    override fun onBindViewHolder(holder: RatingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class RatingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val raterNameText: TextView = itemView.findViewById(R.id.itemRaterName)
        private val ratingBar: RatingBar = itemView.findViewById(R.id.itemRatingBar)
        private val commentText: TextView = itemView.findViewById(R.id.itemComment)
        private val dateText: TextView = itemView.findViewById(R.id.itemDate)

        fun bind(item: Rating) {
            ratingBar.rating = item.rating
            commentText.text = if (item.comments.isNullOrBlank()) "No comments" else item.comments
            dateText.text = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(item.timestamp))

            val displayName = if (item.anonymous) maskName(item.raterName) else item.raterName
            raterNameText.text = if (displayName.isNullOrBlank()) "Rater: Anonymous" else "Rater: $displayName"
        }

        private fun maskName(name: String?): String {
            if (name.isNullOrBlank()) return "Anonymous"
            val cleaned = name.replace(" ", "")
            if (cleaned.length <= 1) return cleaned
            val first = cleaned.first()
            val last = cleaned.last()
            val stars = "*".repeat(maxOf(cleaned.length - 2, 1))
            return "$first$stars$last"
        }
    }
}