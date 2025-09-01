package com.btsi.swiftcabalpha

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ProfileActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var profileImageView: ImageView
    private lateinit var changeImageButton: Button
    private lateinit var profileFullNameEditText: EditText
    private lateinit var profileMobileEditText: EditText
    private lateinit var profileEmailTextView: TextView
    private lateinit var updateEmailButton: Button
    private lateinit var changePasswordButton: Button
    private lateinit var saveProfileButton: Button


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        profileImageView = findViewById(R.id.profileImageView)
        changeImageButton = findViewById(R.id.changeImageButton)
        profileFullNameEditText = findViewById(R.id.profileFullNameEditText)
        profileMobileEditText = findViewById(R.id.profileMobileEditText)
        profileEmailTextView = findViewById(R.id.profileEmailTextView)
        updateEmailButton = findViewById(R.id.updateEmailButton)
        changePasswordButton = findViewById(R.id.changePasswordButton)
        saveProfileButton = findViewById(R.id.saveProfileButton)

        loadUserProfile()

        saveProfileButton.setOnClickListener {
            saveUserProfile()
        }

        // Placeholder OnClickListeners for buttons to be implemented later
        changeImageButton.setOnClickListener {
            Toast.makeText(this, "Change Image functionality to be implemented", Toast.LENGTH_SHORT).show()
        }

        updateEmailButton.setOnClickListener {
            Toast.makeText(this, "Update Email functionality to be implemented", Toast.LENGTH_SHORT).show()
        }

        changePasswordButton.setOnClickListener {
            Toast.makeText(this, "Change Password functionality to be implemented", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadUserProfile() {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            // Display Email from Auth
            profileEmailTextView.text = currentUser.email ?: "Email not available"

            // Load Full Name and Mobile Number from Firestore
            db.collection("users").document(currentUser.uid)
                .get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                        val fullName = document.getString("fullName")
                        val mobileNumber = document.getString("mobileNumber")

                        profileFullNameEditText.setText(fullName ?: "")
                        profileMobileEditText.setText(mobileNumber ?: "")
                        // TODO: Load profile image URL from Firestore and display using Glide/Picasso
                    } else {
                        Toast.makeText(this, "Profile data not found", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error loading profile: ${e.message}", Toast.LENGTH_LONG).show()
                }
        } else {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show()
            // Optionally, redirect to login screen
            // finish()
        }
    }

    private fun saveUserProfile() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(this, "Not logged in, cannot save profile.", Toast.LENGTH_SHORT).show()
            return
        }

        val newFullName = profileFullNameEditText.text.toString().trim()
        val newMobileNumber = profileMobileEditText.text.toString().trim()

        if (newFullName.isBlank()) {
            profileFullNameEditText.error = "Full name cannot be empty"
            profileFullNameEditText.requestFocus()
            return
        }
        // Mobile number can be optional, or add validation if needed

        val userProfileUpdates = hashMapOf<String, Any>(
            "fullName" to newFullName,
            "mobileNumber" to newMobileNumber
            // Add other fields to update here if necessary e.g., "profileImageUrl"
        )

        db.collection("users").document(currentUser.uid)
            .update(userProfileUpdates)
            .addOnSuccessListener {
                Toast.makeText(this, "Profile updated successfully", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error updating profile: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }
}
