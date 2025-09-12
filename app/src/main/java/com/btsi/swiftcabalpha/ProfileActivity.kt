package com.btsi.swiftcabalpha

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
// import androidx.appcompat.widget.Toolbar // No toolbar as per revert
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
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

        // Toolbar setup is intentionally removed based on prior reversions
        // val toolbarProfile: Toolbar = findViewById(R.id.toolbar_profile)
        // setSupportActionBar(toolbarProfile)
        // supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // supportActionBar?.title = "Edit Profile"

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
            // Consider implementing proper account deletion or a confirmation dialog
            Toast.makeText(this, "Delete account functionality to be implemented.", Toast.LENGTH_SHORT).show()
        }
    }

    // onOptionsItemSelected is intentionally removed based on prior reversions
    // override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
    //    if (item.itemId == android.R.id.home) {
    //        finish() // Or navigateUp()
    //        return true
    //    }
    //    return super.onOptionsItemSelected(item)
    // }

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
                        val profileImageUrl = document.getString("profileImageUrl")

                        initialFullName = fullName ?: ""
                        initialMobileNumber = mobileNumber ?: ""

                        profileFullNameEditText.setText(fullName)
                        profileMobileEditText.setText(mobileNumber)
                        profileEmailTextView.text = "Email: $email" // Consider using string resource

                        if (!profileImageUrl.isNullOrEmpty()) {
                            Glide.with(this).load(profileImageUrl).circleCrop().placeholder(R.drawable.default_profile).error(R.drawable.default_profile).into(profileImageView)
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
            // No redirect if no changes.
            return
        }

        if (hasNewImage) { // Check if new image is selected
            selectedImageUri?.let { uri -> // Ensure selectedImageUri is not null
                uploadImageAndSaveProfile(userId, fullName, mobileNumber, uri)
            }
        } else { // No new image, only text might have changed
            if (hasTextChanged) {
                 val userUpdates = hashMapOf<String, Any>(
                    "fullName" to fullName,
                    "mobileNumber" to mobileNumber
                )
                updateFirestore(userId, userUpdates)
            }
        }
    }

    private fun uploadImageAndSaveProfile(userId: String, fullName: String, mobileNumber: String, imageUri: Uri) {
        val fileName = "profile_images/${UUID.randomUUID()}.jpg" // Using imageUri passed as parameter
        val imageRef = storage.reference.child(fileName)

        imageRef.putFile(imageUri)
            .addOnSuccessListener {
                imageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                    val userUpdates = hashMapOf<String, Any>(
                        "fullName" to fullName,
                        "mobileNumber" to mobileNumber,
                        "profileImageUrl" to downloadUri.toString()
                    )
                    updateFirestore(userId, userUpdates)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Image upload failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun updateFirestore(userId: String, updates: Map<String, Any>) {
        db.collection("users").document(userId)
            .set(updates, SetOptions.merge())
            .addOnSuccessListener {
                Toast.makeText(this, "Profile updated successfully", Toast.LENGTH_SHORT).show()
                // Redirect to HomeActivity
                val intent = Intent(this, HomeActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                finish() // Finish ProfileActivity so user can't go back to it from HomeActivity
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error updating profile: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }
}
