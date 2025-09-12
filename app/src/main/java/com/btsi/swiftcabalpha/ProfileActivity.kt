package com.btsi.swiftcabalpha

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage

class ProfileActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var storage: FirebaseStorage

    private lateinit var profileImageView: ImageView
    private lateinit var changeImageButton: Button
    private lateinit var profileFullNameEditText: EditText
    private lateinit var profileMobileEditText: EditText
    private lateinit var profileEmailTextView: TextView
    private lateinit var saveProfileButton: Button
    private lateinit var deleteAccountButton: Button // Added

    private var selectedImageUri: Uri? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            Glide.with(this)
                .load(it)
                .placeholder(R.drawable.default_profile)
                .error(R.drawable.default_profile)
                .circleCrop()
                .into(profileImageView)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()

        profileImageView = findViewById(R.id.profileImageView)
        changeImageButton = findViewById(R.id.changeImageButton)
        profileFullNameEditText = findViewById(R.id.profileFullNameEditText)
        profileMobileEditText = findViewById(R.id.profileMobileEditText)
        profileEmailTextView = findViewById(R.id.profileEmailTextView)
        saveProfileButton = findViewById(R.id.saveProfileButton)
        deleteAccountButton = findViewById(R.id.deleteAccountButton) // Added

        loadUserProfile()

        saveProfileButton.setOnClickListener {
            saveUserProfile()
        }

        changeImageButton.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        deleteAccountButton.setOnClickListener { // Added
            AlertDialog.Builder(this)
                .setTitle("Delete Account?")
                .setMessage("Are you sure you want to delete your account? This action is irreversible and will permanently delete all your data.")
                .setPositiveButton("Delete Account") { dialog, _ ->
                    Log.d("ProfileActivity", "User confirmed account deletion. Proceeding to re-authentication step.")
                    promptForPasswordAndReauthenticate()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    Log.d("ProfileActivity", "User cancelled account deletion.")
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun promptForPasswordAndReauthenticate() { // Added
        val currentUser = auth.currentUser
        if (currentUser == null || currentUser.email == null) {
            Toast.makeText(this, "User not found or email missing.", Toast.LENGTH_SHORT).show()
            return
        }

        val passwordDialogBuilder = AlertDialog.Builder(this)
        passwordDialogBuilder.setTitle("Re-enter Password")
        passwordDialogBuilder.setMessage("For security, please enter your password to delete your account.")

        val passwordInput = EditText(this)
        passwordInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        passwordInput.hint = "Password"
        // Add some padding to the EditText
        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        layoutParams.setMargins(50, 20, 50, 20) // Left, Top, Right, Bottom margins
        passwordInput.layoutParams = layoutParams

        // Create a LinearLayout to hold the EditText, allowing for padding around the EditText
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.addView(passwordInput)
        passwordDialogBuilder.setView(container)

        passwordDialogBuilder.setPositiveButton("Confirm Delete") { dialog, _ ->
            val password = passwordInput.text.toString()
            if (password.isBlank()) {
                Toast.makeText(this, "Password cannot be empty.", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }

            val credential = EmailAuthProvider.getCredential(currentUser.email!!, password)
            currentUser.reauthenticate(credential)
                .addOnSuccessListener {
                    Log.d("ProfileActivity", "User re-authenticated successfully.")
                    // Proceed with actual deletion
                    deleteUserAccountData()
                }
                .addOnFailureListener { e ->
                    Log.e("ProfileActivity", "Re-authentication failed.", e)
                    if (e is FirebaseAuthInvalidCredentialsException) {
                        Toast.makeText(this, "Incorrect password. Please try again.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Re-authentication failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            dialog.dismiss()
        }
        passwordDialogBuilder.setNegativeButton("Cancel") { dialog, _ ->
            Log.d("ProfileActivity", "User cancelled re-authentication for deletion.")
            dialog.dismiss()
        }
        passwordDialogBuilder.show()
    }

    private fun deleteUserAccountData() {
        val user = auth.currentUser ?: return
        val userId = user.uid

        // 1. Delete Firestore Data
        db.collection("users").document(userId)
            .delete()
            .addOnSuccessListener {
                Log.d("ProfileActivity", "User data from Firestore deleted successfully.")
                // 2. Delete Storage Data (Profile Image)
                val storageRef = storage.reference.child("profile_images/$userId/profile.jpg")
                storageRef.delete()
                    .addOnSuccessListener {
                        Log.d("ProfileActivity", "User profile image from Storage deleted successfully.")
                        // 3. Delete Auth User
                        deleteAuthUser(user)
                    }
                    .addOnFailureListener {
                        Log.w("ProfileActivity", "Failed to delete profile image or it did not exist.", it)
                        // Proceed to delete auth user even if image deletion fails (it might not exist)
                        deleteAuthUser(user)
                    }
            }
            .addOnFailureListener { e ->
                Log.e("ProfileActivity", "Failed to delete user data from Firestore.", e)
                Toast.makeText(this, "Failed to delete profile data: ${e.message}", Toast.LENGTH_LONG).show()
                // Do not proceed to delete auth user if Firestore deletion fails, as it might leave orphaned auth account
            }
    }

    private fun deleteAuthUser(user: com.google.firebase.auth.FirebaseUser) {
        user.delete()
            .addOnSuccessListener {
                Log.d("ProfileActivity", "Firebase Auth user deleted successfully.")
                Toast.makeText(this, "Account deleted successfully.", Toast.LENGTH_LONG).show()
                auth.signOut() // Sign out locally
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .addOnFailureListener { e ->
                Log.e("ProfileActivity", "Failed to delete Firebase Auth user.", e)
                Toast.makeText(this, "Failed to delete account: ${e.message}", Toast.LENGTH_LONG).show()
                // This is a critical failure. The user might be in an inconsistent state.
                // Consider prompting them to try signing in again and retrying the deletion.
            }
    }


    private fun loadUserProfile() {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            profileEmailTextView.text = currentUser.email ?: "Email not available"

            db.collection("users").document(currentUser.uid)
                .get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                        val fullName = document.getString("fullName")
                        val mobileNumber = document.getString("mobileNumber")
                        val imageUrl = document.getString("profileImageUrl")

                        profileFullNameEditText.setText(fullName ?: "")
                        profileMobileEditText.setText(mobileNumber ?: "")

                        if (!imageUrl.isNullOrEmpty()) {
                            Glide.with(this@ProfileActivity)
                                .load(imageUrl)
                                .placeholder(R.drawable.swiftcab)
                                .error(R.drawable.swiftcab)
                                .circleCrop()
                                .into(profileImageView)
                        } else {
                            Glide.with(this@ProfileActivity)
                                .load(R.drawable.swiftcab)
                                .circleCrop()
                                .into(profileImageView)
                        }
                    } else {
                        Toast.makeText(this, "Profile data not found", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error loading profile: ${e.message}", Toast.LENGTH_LONG).show()
                }
        } else {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show()
            // Consider redirecting to LoginActivity if not logged in
            // startActivity(Intent(this, LoginActivity::class.java))
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

        if (selectedImageUri != null) {
            val storageRef = storage.reference.child("profile_images/${currentUser.uid}/profile.jpg")
            storageRef.putFile(selectedImageUri!!)
                .addOnSuccessListener { taskSnapshot ->
                    taskSnapshot.storage.downloadUrl.addOnSuccessListener { uri ->
                        val imageUrl = uri.toString()
                        val userProfileUpdates = hashMapOf<String, Any>(
                            "fullName" to newFullName,
                            "mobileNumber" to newMobileNumber,
                            "profileImageUrl" to imageUrl
                        )
                        updateFirestore(currentUser.uid, userProfileUpdates)
                    }.addOnFailureListener { e ->
                         Toast.makeText(this, "Failed to get download URL: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Image upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        } else {
            val userProfileUpdates = hashMapOf<String, Any>(
                "fullName" to newFullName,
                "mobileNumber" to newMobileNumber
            )
            updateFirestore(currentUser.uid, userProfileUpdates)
        }
    }

    private fun updateFirestore(userId: String, updates: Map<String, Any>) {
        db.collection("users").document(userId)
            .update(updates)
            .addOnSuccessListener {
                Toast.makeText(this, "Profile updated successfully", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error updating profile: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }
}
