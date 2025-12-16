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
import com.google.firebase.firestore.Query

class RiderRatingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRiderRatingsBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var adapter: RatingsAdapter

    /**
     * Initializes bindings, toolbar, adapter, and loads ratings submitted by the rider.
     */
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

    /**
     * Loads all ratings created by the current rider and enriches missing names.
     */
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
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                var items = snap.documents.mapNotNull { it.toObject(Rating::class.java) }
                adapter.setShowRatedName(true)
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

                // Resolve rated user names so the rider's name shows always
                val ratedIds = items.map { it.ratedId }.filter { it.isNotBlank() }.distinct()
                if (ratedIds.isNotEmpty()) {
                    val nameMap = mutableMapOf<String, String>()
                    var remaining = ratedIds.size
                    ratedIds.forEach { rid ->
                        firestore.collection("users").document(rid).get()
                            .addOnSuccessListener { doc ->
                                val fullName = doc.getString("name") ?: doc.getString("displayName") ?: ""
                                if (!fullName.isNullOrBlank()) nameMap[rid] = fullName
                            }
                            .addOnCompleteListener {
                                remaining -= 1
                                if (remaining <= 0) {
                                    adapter.setRatedNames(nameMap)
                                }
                            }
                    }
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to load ratings: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            .addOnCompleteListener {
                binding.progressBar.visibility = View.GONE
            }
    }

    /**
     * Handles toolbar up navigation.
     */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
