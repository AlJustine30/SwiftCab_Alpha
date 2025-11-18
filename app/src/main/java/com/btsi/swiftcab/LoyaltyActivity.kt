package com.btsi.swiftcab

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.btsi.swiftcab.databinding.ActivityLoyaltyBinding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class LoyaltyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoyaltyBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    /**
     * Initializes toolbar, loads current points, and sets up redeem actions.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoyaltyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        setupToolbar(binding.toolbarLoyalty)
        loadCurrentPoints()
        setupRedeemButtons()
    }

    /**
     * Configures the toolbar title and up navigation.
     */
    private fun setupToolbar(toolbar: MaterialToolbar) {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Loyalty & Rewards"
        toolbar.setNavigationOnClickListener { finish() }
    }

    /**
     * Loads loyalty points and any scheduled next‑booking discount.
     */
    private fun loadCurrentPoints() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, "Not signed in", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        binding.progressBar.visibility = View.VISIBLE
        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val pts = (doc.getLong("loyaltyPoints") ?: 0L)
                binding.textViewCurrentPoints.text = "$pts"
                val next = (doc.getLong("nextBookingDiscountPercent") ?: 0L)
                if (next > 0L) {
                    binding.textViewNextDiscount.text = "Next booking discount: $next%"
                    binding.textViewNextDiscount.visibility = View.VISIBLE
                } else {
                    binding.textViewNextDiscount.visibility = View.GONE
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to load points: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            .addOnCompleteListener { binding.progressBar.visibility = View.GONE }
    }

    /**
     * Wires redeem buttons for 5%, 10%, and 20% discounts.
     */
    private fun setupRedeemButtons() {
        binding.buttonRedeem5.setOnClickListener { redeem(50, 5) }
        binding.buttonRedeem10.setOnClickListener { redeem(100, 10) }
        binding.buttonRedeem20.setOnClickListener { redeem(200, 20) }
    }

    /**
     * Executes a transaction to redeem points for a discount, ensuring no overdraft
     * and that only one upcoming discount is scheduled at a time.
     */
    private fun redeem(pointsRequired: Long, discountPercent: Long) {
        val uid = auth.currentUser?.uid ?: return
        binding.progressBar.visibility = View.VISIBLE
        // Transactionally deduct points and set next booking discount
        val userRef = firestore.collection("users").document(uid)
        firestore.runTransaction { tx ->
            val snap = tx.get(userRef)
            val currentPts = snap.getLong("loyaltyPoints") ?: 0L
            val existingDiscount = snap.getLong("nextBookingDiscountPercent") ?: 0L
            if (currentPts < pointsRequired) {
                throw IllegalStateException("Not enough points")
            }
            if (existingDiscount > 0L) {
                throw IllegalStateException("A discount is already scheduled")
            }
            tx.update(userRef, mapOf(
                "loyaltyPoints" to (currentPts - pointsRequired),
                "nextBookingDiscountPercent" to discountPercent
            ))
            null
        }
            .addOnSuccessListener {
                Toast.makeText(this, "Redeemed $pointsRequired pts for $discountPercent% off next booking", Toast.LENGTH_LONG).show()
                loadCurrentPoints()
            }
            .addOnFailureListener { e ->
                val msg = when {
                    e.message?.contains("Not enough points", true) == true -> "Not enough points"
                    e.message?.contains("already scheduled", true) == true -> "You already have a discount scheduled"
                    else -> e.message ?: "Redemption failed"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .addOnCompleteListener { binding.progressBar.visibility = View.GONE }
    }
}
