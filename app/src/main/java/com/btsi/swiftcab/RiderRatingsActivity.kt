package com.btsi.swiftcab

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.btsi.swiftcab.databinding.ActivityRiderRatingsBinding
import com.btsi.swiftcab.models.Rating
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class RiderRatingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRiderRatingsBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var adapter: RatingsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRiderRatingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarRatings)
        supportActionBar?.title = "My Ratings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        adapter = RatingsAdapter()
        binding.recyclerViewRatings.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewRatings.adapter = adapter

        loadMySubmittedRatings()
    }

    private fun loadMySubmittedRatings() {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrEmpty()) {
            Toast.makeText(this, "Not signed in.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        binding.progressBar.visibility = View.VISIBLE
        firestore.collection("ratings")
            .whereEqualTo("raterId", uid)
            .get()
            .addOnSuccessListener { snap ->
                var items = snap.documents.mapNotNull { it.toObject(Rating::class.java) }
                adapter.submitList(items)
                binding.textViewSummary.text = "You have submitted ${items.size} ratings"

                // Fallback: enrich my name if missing in earlier ratings
                if (items.any { it.raterName.isBlank() }) {
                    firestore.collection("users").document(uid).get()
                        .addOnSuccessListener { doc ->
                            val fullName = doc.getString("name") ?: doc.getString("displayName") ?: ""
                            if (fullName.isNotBlank()) {
                                items = items.map { r -> if (r.raterName.isBlank()) r.copy(raterName = fullName) else r }
                                adapter.submitList(items)
                                binding.textViewSummary.text = "You have submitted ${items.size} ratings"
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