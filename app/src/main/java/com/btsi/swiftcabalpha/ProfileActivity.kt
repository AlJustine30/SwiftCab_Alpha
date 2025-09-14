package com.btsi.swiftcabalpha

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import java.util.UUID

class ProfileActivity : AppCompatActivity() {

    private lateinit var profileImageView: ImageView
    private lateinit var changeImageButton: Button
    private lateinit var profileFullNameEditText: EditText
    private lateinit var profileMobileEditText: EditText
    private lateinit var profileEmailTextView: TextView
    private lateinit var saveProfileButton: Button
    private lateinit var deleteAccountButton: Button

    private var selectedImageUri: Uri? = null
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var storage: FirebaseStorage

    private var initialFullName: String? = ""
    private var initialMobileNumber: String? = ""
    private var currentProfileImageUrl: String? = null // To store the current profile image URL for deletion

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedImageUri = uri
                Glide.with(this).load(uri).circleCrop().into(profileImageView)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        profileImageView = findViewById(R.id.profileImageView)
        changeImageButton = findViewById(R.id.changeImageButton)
        profileFullNameEditText = findViewById(R.id.profileFullNameEditText)
        profileMobileEditText = findViewById(R.id.profileMobileEditText)
        profileEmailTextView = findViewById(R.id.profileEmailTextView)
        saveProfileButton = findViewById(R.id.saveProfileButton)
        deleteAccountButton = findViewById(R.id.deleteAccountButton)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()

        loadUserProfile()

        changeImageButton.setOnClickListener {
            openImageChooser()
        }

        saveProfileButton.setOnClickListener {
            saveUserProfile()
        }

        deleteAccountButton.setOnClickListener {
            showDeleteConfirmationDialog()
        }
    }

    private fun openImageChooser() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(intent)
    }

    private fun loadUserProfile() {
        val userId = auth.currentUser?.uid
        if (userId != null) {
            db.collection("users").document(userId).get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                        val fullName = document.getString("fullName")
                        val mobileNumber = document.getString("mobileNumber")
                        val email = document.getString("email")
                        currentProfileImageUrl = document.getString("profileImageUrl") // Store for deletion

                        initialFullName = fullName ?: ""
                        initialMobileNumber = mobileNumber ?: ""

                        profileFullNameEditText.setText(fullName)
                        profileMobileEditText.setText(mobileNumber)
                        profileEmailTextView.text = "Email: $email"

                        if (!currentProfileImageUrl.isNullOrEmpty()) {
                            Glide.with(this).load(currentProfileImageUrl).circleCrop().placeholder(R.drawable.default_profile).error(R.drawable.default_profile).into(profileImageView)
                        } else {
                            Glide.with(this).load(R.drawable.default_profile).circleCrop().into(profileImageView)
                        }
                    } else {
                        Toast.makeText(this, "Profile not found.", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error loading profile: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun saveUserProfile() {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show()
            return
        }

        val fullName = profileFullNameEditText.text.toString().trim()
        val mobileNumber = profileMobileEditText.text.toString().trim()

        val hasTextChanged = fullName != initialFullName || mobileNumber != initialMobileNumber
        val hasNewImage = selectedImageUri != null

        if (!hasTextChanged && !hasNewImage) {
            Toast.makeText(this, "No changes to save.", Toast.LENGTH_SHORT).show()
            return
        }

        if (hasNewImage) {
            selectedImageUri?.let { uri ->
                uploadImageAndSaveProfile(userId, fullName, mobileNumber, uri)
            }
        } else {
            if (hasTextChanged) {
                 val userUpdates = hashMapOf<String, Any>(
                    "fullName" to fullName,
                    "mobileNumber" to mobileNumber
                )
                updateFirestore(userId, userUpdates, false) // false as no new image URL
            }
        }
    }

    private fun uploadImageAndSaveProfile(userId: String, fullName: String, mobileNumber: String, imageUri: Uri) {
        val fileName = "profile_images/${UUID.randomUUID()}.jpg"
        val imageRef = storage.reference.child(fileName)

        imageRef.putFile(imageUri)
            .addOnSuccessListener {
                imageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                    val userUpdates = hashMapOf<String, Any>(
                        "fullName" to fullName,
                        "mobileNumber" to mobileNumber,
                        "profileImageUrl" to downloadUri.toString()
                    )
                    // Delete old image if a new one is uploaded and an old one existed
                    if (!currentProfileImageUrl.isNullOrEmpty()) {
                        try {
                            storage.getReferenceFromUrl(currentProfileImageUrl!!).delete()
                                .addOnSuccessListener { Log.d("ProfileActivity", "Old profile image deleted.") }
                                .addOnFailureListener { e -> Log.e("ProfileActivity", "Failed to delete old profile image: ${e.message}") }
                        } catch (e: IllegalArgumentException) {
                            Log.e("ProfileActivity", "Invalid URL for old profile image: ${e.message}")
                        } catch (e: StorageException) {
                            Log.e("ProfileActivity", "Storage exception deleting old image: ${e.message}")
                        }
                    }
                    updateFirestore(userId, userUpdates, true)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Image upload failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun updateFirestore(userId: String, updates: Map<String, Any>, isNewImageUploaded: Boolean) {
        db.collection("users").document(userId)
            .set(updates, SetOptions.merge())
            .addOnSuccessListener {
                Toast.makeText(this, "Profile updated successfully", Toast.LENGTH_SHORT).show()
                if (isNewImageUploaded && updates.containsKey("profileImageUrl")) {
                    currentProfileImageUrl = updates["profileImageUrl"] as String? // Update currentProfileImageUrl
                }
                 val intent = Intent(this, HomeActivity::class.java)
                 intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                 startActivity(intent)
                 finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error updating profile: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun showDeleteConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Delete Account")
            .setMessage("Are you sure you want to delete your account? This action is permanent and cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                proceedWithAccountDeletion()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun proceedWithAccountDeletion() {
        val user = auth.currentUser
        val userId = user?.uid

        if (userId == null) {
            Toast.makeText(this, "User not found. Cannot delete account.", Toast.LENGTH_LONG).show()
            return
        }

        // 1. Delete Firestore Document
        db.collection("users").document(userId).delete()
            .addOnSuccessListener {
                Log.d("ProfileActivity", "User document successfully deleted from Firestore.")
                // 2. Delete Profile Image from Storage (if exists)
                deleteProfileImageFromStorage {
                    // 3. Delete Firebase Auth User
                    deleteFirebaseAuthUser()
                }
            }
            .addOnFailureListener { e ->
                Log.e("ProfileActivity", "Error deleting user document from Firestore: ${e.message}")
                Toast.makeText(this, "Failed to delete user data: ${e.message}. Please try again.", Toast.LENGTH_LONG).show()
            }
    }

    private fun deleteProfileImageFromStorage(onComplete: () -> Unit) {
        if (!currentProfileImageUrl.isNullOrEmpty()) {
            try {
                val imageRef = storage.getReferenceFromUrl(currentProfileImageUrl!!)
                imageRef.delete()
                    .addOnSuccessListener {
                        Log.d("ProfileActivity", "User profile image successfully deleted from Storage.")
                        onComplete()
                    }
                    .addOnFailureListener { e ->
                        Log.e("ProfileActivity", "Error deleting profile image from Storage: ${e.message}")
                        // Still proceed to delete Auth user even if image deletion fails,
                        // as Auth/Firestore data is more critical.
                        Toast.makeText(this, "Could not delete profile image, but proceeding.", Toast.LENGTH_SHORT).show()
                        onComplete()
                    }
            } catch (e: IllegalArgumentException) {
                Log.e("ProfileActivity", "Invalid profile image URL for deletion: ${e.message}")
                onComplete() // Proceed if URL is invalid
            } catch (e: StorageException) {
                Log.e("ProfileActivity", "Storage exception for profile image deletion: ${e.message}")
                onComplete() // Proceed on storage error
            }

        } else {
            Log.d("ProfileActivity", "No profile image to delete from Storage.")
            onComplete() // No image, just proceed
        }
    }

    private fun deleteFirebaseAuthUser() {
        auth.currentUser?.delete()
            ?.addOnSuccessListener {
                Log.d("ProfileActivity", "User account successfully deleted from Firebase Auth.")
                Toast.makeText(this, "Account deleted successfully.", Toast.LENGTH_LONG).show()
                redirectToLogin()
            }
            ?.addOnFailureListener { e ->
                Log.e("ProfileActivity", "Error deleting user account from Firebase Auth: ${e.message}")
                Toast.makeText(this, "Failed to delete account: ${e.message}. Please re-authenticate or try again.", Toast.LENGTH_LONG).show()
                if (e.message?.contains("RECENT_LOGIN_REQUIRED", ignoreCase = true) == true) {
                    Toast.makeText(this, "This operation requires a recent login. Please log out and log back in to delete your account.", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun redirectToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
