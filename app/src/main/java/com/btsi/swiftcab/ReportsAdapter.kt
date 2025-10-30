package com.btsi.swiftcab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.btsi.swiftcab.models.Report
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportsAdapter : ListAdapter<Report, ReportsAdapter.ReportViewHolder>(DiffCallback) {

    // Map of driverId -> driverName provided by the activity after resolving.
    private var driverNames: Map<String, String> = emptyMap()

    fun setDriverNames(names: Map<String, String>) {
        driverNames = names
        // Names impact displayed text; refresh current list.
        notifyDataSetChanged()
    }

    object DiffCallback : DiffUtil.ItemCallback<Report>() {
        override fun areItemsTheSame(oldItem: Report, newItem: Report): Boolean {
            return oldItem.bookingId == newItem.bookingId && oldItem.timestamp == newItem.timestamp
        }

        override fun areContentsTheSame(oldItem: Report, newItem: Report): Boolean {
            return oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReportViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_report, parent, false)
        return ReportViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReportViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ReportViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textCategory: TextView = itemView.findViewById(R.id.textCategory)
        private val textMessage: TextView = itemView.findViewById(R.id.textMessage)
        private val textMeta: TextView = itemView.findViewById(R.id.textMeta)
        private val textStatus: TextView = itemView.findViewById(R.id.textStatus)

        private val dateFormat = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())

        fun bind(report: Report) {
            textCategory.text = report.category ?: "Unspecified"
            textMessage.text = report.message ?: ""
            val dateStr = dateFormat.format(Date(report.timestamp ?: 0L))
            val resolvedName = driverNames[report.driverId]
            val driverLabel = when {
                !resolvedName.isNullOrBlank() -> "Driver: $resolvedName"
                report.driverId.isNotBlank() -> "Driver: ${report.driverId}"
                else -> "Driver: N/A"
            }
            val statusLabel = when (report.status.lowercase(Locale.getDefault())) {
                "resolved" -> "Resolved"
                "open" -> "Open"
                else -> if (report.resolvedAt != null) "Resolved" else "Open"
            }
            textMeta.text = "$dateStr  •  $driverLabel  •  Booking: ${report.bookingId ?: "N/A"}"
            textStatus.text = statusLabel
            if (statusLabel == "Resolved") {
                textStatus.setBackgroundResource(R.drawable.status_chip_resolved)
            } else {
                textStatus.setBackgroundResource(R.drawable.status_chip_open)
            }
        }
    }
}
