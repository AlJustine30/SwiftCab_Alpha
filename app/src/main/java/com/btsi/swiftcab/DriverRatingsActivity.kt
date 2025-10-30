package com.btsi.swiftcab

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.btsi.swiftcab.databinding.ActivityDriverRatingsBinding
import com.btsi.swiftcab.models.Rating
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class DriverRatingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDriverRatingsBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var adapter: RatingsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriverRatingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarRatings)
        supportActionBar?.title = if (intent.getStringExtra("TARGET_USER_ID").isNullOrBlank()) "My Ratings" else "Driver Ratings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        adapter = RatingsAdapter()
        binding.recyclerViewRatings.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewRatings.adapter = adapter

        loadRatingsForTarget()
    }

    private fun loadRatingsForTarget() {
        val targetUserId = intent.getStringExtra("TARGET_USER_ID")
        val uidToUse = targetUserId?.takeIf { it.isNotBlank() } ?: auth.currentUser?.uid
        if (uidToUse.isNullOrEmpty()) {
            Toast.makeText(this, "Not signed in.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        binding.progressBar.visibility = View.VISIBLE
        firestore.collection("ratings")
            .whereEqualTo("ratedId", uidToUse)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                var items = snap.documents.mapNotNull { it.toObject(Rating::class.java) }
                adapter.submitList(items)
                val count = items.size
                if (count > 0) {
                    val avg = items.map { it.rating }.average()
                    binding.ratingBarAverage.rating = avg.toFloat()
                    binding.textViewAverage.text = String.format("%.1f stars (%d ratings)", avg, count)
                } else {
                    binding.ratingBarAverage.rating = 0f
                    binding.textViewAverage.text = "No ratings yet"
                }

                // Fallback: enrich missing rater names from users collection
                val missingIds = items.filter { it.raterName.isBlank() }.map { it.raterId }.distinct()
                missingIds.forEach { userId ->
                    firestore.collection("users").document(userId).get()
                        .addOnSuccessListener { doc ->
                            val fullName = doc.getString("name") ?: doc.getString("displayName") ?: ""
                            if (fullName.isNotBlank()) {
                                items = items.map { r -> if (r.raterId == userId && r.raterName.isBlank()) r.copy(raterName = fullName) else r }
                                adapter.submitList(items)
                            }
                        }
                        .addOnFailureListener { /* ignore */ }
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to load ratings: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            .addOnCompleteListener {
                binding.progressBar.visibility = View.GONE
            }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
