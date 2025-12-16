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
    private var showRatedName: Boolean = false
    private var ratedNames: Map<String, String> = emptyMap()

    fun setShowRatedName(flag: Boolean) {
        showRatedName = flag
        notifyDataSetChanged()
    }

    fun setRatedNames(names: Map<String, String>) {
        ratedNames = names
        notifyDataSetChanged()
    }
    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Rating>() {
            override fun areItemsTheSame(oldItem: Rating, newItem: Rating): Boolean =
                oldItem.bookingId == newItem.bookingId && oldItem.timestamp == newItem.timestamp
            override fun areContentsTheSame(oldItem: Rating, newItem: Rating): Boolean =
                oldItem == newItem
        }
    }

    /**
     * Inflates a rating item layout and creates its ViewHolder.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RatingViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_rating, parent, false)
        return RatingViewHolder(view)
    }

    /**
     * Binds the rating item at the given position to the ViewHolder.
     */
    override fun onBindViewHolder(holder: RatingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class RatingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val raterNameText: TextView = itemView.findViewById(R.id.itemRaterName)
        private val ratingBar: RatingBar = itemView.findViewById(R.id.itemRatingBar)
        private val commentText: TextView = itemView.findViewById(R.id.itemComment)
        private val dateText: TextView = itemView.findViewById(R.id.itemDate)

        /**
         * Binds rating value, rater name (with anonymity), comments, and date.
         */
        fun bind(item: Rating) {
            ratingBar.rating = item.rating
            commentText.text = if (item.comments.isNullOrBlank()) "No comments" else item.comments
            dateText.text = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(item.timestamp))

            if ((itemView.parent as? RecyclerView)?.adapter is RatingsAdapter && (itemView.parent as RecyclerView).adapter != null) {
                val adapter = (itemView.parent as RecyclerView).adapter as RatingsAdapter
                if (adapter.showRatedName) {
                    val riderName = adapter.ratedNames[item.ratedId] ?: ""
                    raterNameText.text = if (riderName.isBlank()) "Rider: Unknown" else "Rider: $riderName"
                    return
                }
            }

            val displayName = if (item.anonymous) maskName(item.raterName) else item.raterName
            raterNameText.text = if (displayName.isNullOrBlank()) "Rater: Anonymous" else "Rater: $displayName"
        }

        /**
         * Obscures the rater name when anonymous by masking middle characters.
         */
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
