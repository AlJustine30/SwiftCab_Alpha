package com.btsi.swiftcab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage

class ProfileActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var storage: FirebaseStorage

    private var selectedImageUri: Uri? = null
    private lateinit var imagePickerLauncher: ActivityResultLauncher<Intent>

    private val NOTIFICATION_CHANNEL_ID = "profile_update_channel"

    private var isDriver: Boolean = false

    // Track original values to detect unsaved changes
    private var initialName: String = ""
    private var initialEmail: String = ""
    private var initialPhone: String = ""
    private var initialImageUrl: String? = null

    /**
     * Initializes profile UI, loads passenger/driver details, configures image picker,
     * and sets up change password, save, and logout actions.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbarProfile)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()

        createNotificationChannel()

        val userNameEditText = findViewById<TextInputEditText>(R.id.profileFullNameEditText)
        val userEmailEditText = findViewById<TextInputEditText>(R.id.profileEmailEditText)
        val userPhoneEditText = findViewById<TextInputEditText>(R.id.profileMobileEditText)
        val userImageView = findViewById<ImageView>(R.id.profileImageView)
        val logoutButton = findViewById<Button>(R.id.logoutButton)
        val saveProfileButton = findViewById<Button>(R.id.saveProfileButton)
        val changePasswordButton = findViewById<Button>(R.id.changePasswordButton)
        val changeImageButton = findViewById<Button>(R.id.changeImageButton)
        val driverVehicleText = findViewById<TextView>(R.id.driverVehicleText)
        val driverAdminDetailsHeader = findViewById<TextView>(R.id.driverAdminDetailsHeader)

        // Default: hide driver admin section until confirmed
        driverAdminDetailsHeader.visibility = View.GONE
        driverVehicleText.visibility = View.GONE

        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    selectedImageUri = uri
                    Glide.with(this).load(uri).into(userImageView)
                    if (isDriver) {
                        // Show confirmation dialog before uploading image for drivers
                        showConfirmDialog(
                            title = "Update Profile Picture",
                            message = "Do you want to replace your profile picture?",
                            positive = "Update",
                            onConfirm = { uploadImageOnly(uri) }
                        )
                    }
                }
            }
        }

        changeImageButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"
            intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png"))
            imagePickerLauncher.launch(intent)
        }

        val userId = auth.currentUser?.uid
        userId?.let {
            // Load basic user profile (for passengers or fallback)
            db.collection("users").document(it).get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                        val name = document.getString("name")
                        val email = document.getString("email")
                        val phone = document.getString("phone")
                        val profileImageUrl = document.getString("profileImageUrl")
                        val userRole = document.getString("role")

                        userNameEditText.setText(name)
                        userEmailEditText.setText(email)
                        userPhoneEditText.setText(phone)

                        // Record initial values for unsaved-change detection
                        initialName = name ?: ""
                        initialEmail = email ?: ""
                        initialPhone = phone ?: ""
                        initialImageUrl = profileImageUrl

                        profileImageUrl?.let { url ->
                            Glide.with(this).load(url).into(userImageView)
                        }

                        // Preliminarily set role if present
                        isDriver = (userRole?.equals("Driver", ignoreCase = true) == true)
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error getting user data: ${e.message}", Toast.LENGTH_SHORT).show()
                }

            // Load admin-managed driver profile (read-only for name/email/phone)
            db.collection("drivers").document(it).get()
                .addOnSuccessListener { driverDoc ->
                    if (driverDoc != null && driverDoc.exists()) {
                        isDriver = true

                        val dName = driverDoc.getString("name")
                        val dPhone = driverDoc.getString("phone")
                        val dEmail = driverDoc.getString("email") ?: driverDoc.getString("email1")
                        val vehicleMap = driverDoc.get("vehicle") as? Map<*, *>
                        val make = vehicleMap?.get("make") as? String ?: ""
                        val model = vehicleMap?.get("model") as? String ?: ""
                        val year = vehicleMap?.get("year")?.toString() ?: ""
                        val color = vehicleMap?.get("color") as? String ?: ""
                        val licensePlate = vehicleMap?.get("licensePlate") as? String ?: ""
                        val parts = listOf(year, make, model).filter { it.isNotBlank() }.joinToString(" ")
                        val vehicleSummary = listOf(parts, color, licensePlate).filter { it.isNotBlank() }.joinToString(", ")

                        // Show admin driver details section
                        driverAdminDetailsHeader.visibility = View.VISIBLE
                        driverVehicleText.visibility = View.VISIBLE
                        driverVehicleText.text = if (vehicleSummary.isNotBlank()) "Vehicle: $vehicleSummary" else "Vehicle: N/A"

                        // Populate fields with admin values and make them read-only
                        if (!dName.isNullOrBlank()) userNameEditText.setText(dName)
                        if (!dEmail.isNullOrBlank()) userEmailEditText.setText(dEmail)
                        if (!dPhone.isNullOrBlank()) userPhoneEditText.setText(dPhone)
                        userNameEditText.isEnabled = false
                        userEmailEditText.isEnabled = false
                        userPhoneEditText.isEnabled = false

                        // Drivers can change profile image, but need confirmation and cannot save other fields
                        saveProfileButton.visibility = View.GONE
                        changeImageButton.visibility = View.VISIBLE
                    } else {
                        // Not a driver: keep editable and hide admin section
                        isDriver = false
                        driverAdminDetailsHeader.visibility = View.GONE
                        driverVehicleText.visibility = View.GONE
                        userNameEditText.isEnabled = true
                        userEmailEditText.isEnabled = true
                        userPhoneEditText.isEnabled = true
                        saveProfileButton.visibility = View.VISIBLE
                        changeImageButton.visibility = View.VISIBLE
                    }
                }
                .addOnFailureListener {
                    // On failure, default to passenger behavior
                    isDriver = false
                    driverAdminDetailsHeader.visibility = View.GONE
                    driverVehicleText.visibility = View.GONE
                }
        }

        saveProfileButton.setOnClickListener {
            val newName = userNameEditText.text.toString().trim()
            val newEmail = userEmailEditText.text.toString().trim()
            val newPhone = userPhoneEditText.text.toString().trim()

            if (isDriver) {
                Toast.makeText(this, "Contact your admin to update these details.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newName.isNotEmpty() && newEmail.isNotEmpty() && newPhone.isNotEmpty()) {
                // Confirmation before password prompt
                showConfirmDialog(
                    title = "Confirm Profile Changes",
                    message = "Proceed to update your profile details?",
                    positive = "Proceed",
                    onConfirm = { showPasswordConfirmDialog(newName, newEmail, newPhone) }
                )
            } else {
                Toast.makeText(this, "Name, email, and phone cannot be empty.", Toast.LENGTH_SHORT).show()
            }
        }

        changePasswordButton.setOnClickListener {
            showChangePasswordDialog()
        }

        logoutButton.setOnClickListener {
            auth.signOut()
            val intent = Intent(this@ProfileActivity, LoginActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
    /**
     * Handles toolbar back button to check unsaved changes before leaving.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            handleBackNavigation()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Shows a dialog to change password with basic validation.
     */
    private fun showChangePasswordDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_change_password, null)
        val currentPasswordEditText = dialogView.findViewById<EditText>(R.id.currentPasswordEditText)
        val newPasswordEditText = dialogView.findViewById<EditText>(R.id.newPasswordEditText)
        val confirmNewPasswordEditText = dialogView.findViewById<EditText>(R.id.confirmNewPasswordEditText)

        AlertDialog.Builder(this)
            .setTitle("Change Password")
            .setView(dialogView)
            .setPositiveButton("Change") { dialog, _ ->
                val currentPassword = currentPasswordEditText.text.toString()
                val newPassword = newPasswordEditText.text.toString()
                val confirmNewPassword = confirmNewPasswordEditText.text.toString()

                if (newPassword.length < 6) {
                    Toast.makeText(this, "Password should be at least 6 characters", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (newPassword != confirmNewPassword) {
                    Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                reauthenticateAndChangePassword(currentPassword, newPassword)

                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.cancel()
            }
            .create()
            .show()
    }

    /**
     * Reauthenticates the user and updates the password.
     */
    private fun reauthenticateAndChangePassword(currentPassword: String, newPassword: String) {
        val user = auth.currentUser
        val credential = EmailAuthProvider.getCredential(user?.email!!, currentPassword)

        user.reauthenticate(credential).addOnCompleteListener {
            if (it.isSuccessful) {
                user.updatePassword(newPassword).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(this, "Password changed successfully", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Error password not changed", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Authentication failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Prompts for password before saving profile changes.
     */
    private fun showPasswordConfirmDialog(newName: String, newEmail: String, newPhone: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_password_confirm, null)
        val passwordEditText = dialogView.findViewById<EditText>(R.id.passwordEditText)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Confirm") { dialog, _ ->
                val password = passwordEditText.text.toString()
                if (password.isNotEmpty()) {
                    reauthenticateAndSaveChanges(password, newName, newEmail, newPhone)
                } else {
                    Toast.makeText(this, "Password is required.", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.cancel()
            }
            .create()
            .show()
    }

    /**
     * Generic confirmation dialog utility.
     */
    private fun showConfirmDialog(title: String, message: String, positive: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { dialog, _ ->
                onConfirm()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.cancel()
            }
            .create()
            .show()
    }

    /**
     * Reauthenticates and saves profile changes, optionally updating email and image.
     */
    private fun reauthenticateAndSaveChanges(password: String, newName: String, newEmail: String, newPhone: String) {
        val user = auth.currentUser
        val credential = EmailAuthProvider.getCredential(user?.email!!, password)

        user.reauthenticate(credential)
            .addOnSuccessListener {
                if (newEmail != user.email) {
                    user.verifyBeforeUpdateEmail(newEmail).addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Toast.makeText(this, "A confirmation email has been sent to your new email address.", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                selectedImageUri?.let { uri ->
                    uploadImageAndUpdateProfile(uri, newName, newEmail, newPhone)
                } ?: run {
                    updateUserProfile(newName, newEmail, newPhone, null)
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Authentication failed. Please check your password.", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Uploads an image to storage and updates only the profile image URL.
     */
    private fun uploadImageOnly(imageUri: Uri) {
        val userId = auth.currentUser?.uid ?: return
        val storageRef = storage.reference.child("profile_images/$userId")

        storageRef.putFile(imageUri)
            .addOnSuccessListener {
                storageRef.downloadUrl.addOnSuccessListener { downloadUrl ->
                    updateUserProfileImageOnly(downloadUrl.toString())
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Image upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Uploads an image and updates profile fields in Firestore.
     */
    private fun uploadImageAndUpdateProfile(imageUri: Uri, newName: String, newEmail: String, newPhone: String) {
        val userId = auth.currentUser?.uid ?: return
        val storageRef = storage.reference.child("profile_images/$userId")

        storageRef.putFile(imageUri)
            .addOnSuccessListener {
                storageRef.downloadUrl.addOnSuccessListener { downloadUrl ->
                    updateUserProfile(newName, newEmail, newPhone, downloadUrl.toString())
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Image upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Updates the user's profile document and prompts re-login.
     */
    private fun updateUserProfile(newName: String, newEmail: String, newPhone: String, newImageUrl: String?) {
        val userId = auth.currentUser?.uid!!
        val userUpdates = mutableMapOf<String, Any>(
            "name" to newName,
            "phone" to newPhone,
            "email" to newEmail
        )

        newImageUrl?.let {
            userUpdates["profileImageUrl"] = it
        }

        db.collection("users").document(userId).update(userUpdates)
            .addOnSuccessListener {
                Toast.makeText(this, "Profile updated successfully. Please log in again.", Toast.LENGTH_LONG).show()
                showReloginNotification()

                // Reset initial values after a successful save
                initialName = newName
                initialEmail = newEmail
                initialPhone = newPhone
                if (newImageUrl != null) initialImageUrl = newImageUrl
                selectedImageUri = null

                auth.signOut()
                val intent = Intent(this@ProfileActivity, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error updating profile: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Updates only the user's profile image URL in Firestore.
     */
    private fun updateUserProfileImageOnly(newImageUrl: String) {
        val userId = auth.currentUser?.uid ?: return
        val updates = mapOf("profileImageUrl" to newImageUrl)
        if (isDriver) {
            db.collection("drivers").document(userId).set(updates, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener {
                    Toast.makeText(this, "Driver profile image updated.", Toast.LENGTH_SHORT).show()
                    initialImageUrl = newImageUrl
                    selectedImageUri = null
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to update driver image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            db.collection("users").document(userId).update(updates)
                .addOnSuccessListener {
                    Toast.makeText(this, "Profile image updated.", Toast.LENGTH_SHORT).show()
                    initialImageUrl = newImageUrl
                    selectedImageUri = null
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to update profile image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /**
     * Creates a notification channel used for profile update notices.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Profile Updates"
            val descriptionText = "Notifications for profile changes"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Displays a notification prompting the user to re-login after updates.
     */
    private fun showReloginNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.swiftcab)
            .setContentTitle("Profile Updated")
            .setContentText("Please re-login to see changes reflected across the app.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(1, builder.build())
    }

    // Compute and prompt for unsaved changes on back
    private fun computeUnsavedChanges(): Boolean {
        val name = findViewById<TextInputEditText>(R.id.profileFullNameEditText).text?.toString()?.trim() ?: ""
        val email = findViewById<TextInputEditText>(R.id.profileEmailEditText).text?.toString()?.trim() ?: ""
        val phone = findViewById<TextInputEditText>(R.id.profileMobileEditText).text?.toString()?.trim() ?: ""
        var changed = false
        if (!isDriver) {
            changed = (name != initialName || email != initialEmail || phone != initialPhone)
        }
        // Image changed but not saved
        if (selectedImageUri != null) changed = true
        return changed
    }

    private fun handleBackNavigation() {
        if (computeUnsavedChanges()) {
            val name = findViewById<TextInputEditText>(R.id.profileFullNameEditText).text?.toString()?.trim() ?: ""
            val email = findViewById<TextInputEditText>(R.id.profileEmailEditText).text?.toString()?.trim() ?: ""
            val phone = findViewById<TextInputEditText>(R.id.profileMobileEditText).text?.toString()?.trim() ?: ""
            AlertDialog.Builder(this)
                .setTitle("Unsaved Changes")
                .setMessage("You have unsaved changes. Save before leaving?")
                .setPositiveButton("Save") { _, _ ->
                    if (isDriver) {
                        if (selectedImageUri != null) {
                            showPasswordConfirmDialogForImageOnly()
                        } else {
                            Toast.makeText(this, "Contact your admin to update these details.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        showPasswordConfirmDialog(name, email, phone)
                    }
                }
                .setNegativeButton("Exit") { _, _ ->
                    onBackPressedDispatcher.onBackPressed()
                }
                .setNeutralButton("Stay") { dialog, _ -> dialog.dismiss() }
                .show()
        } else {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onBackPressed() {
        handleBackNavigation()
    }

    private fun showPasswordConfirmDialogForImageOnly() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_password_confirm, null)
        val passwordEditText = dialogView.findViewById<EditText>(R.id.passwordEditText)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Confirm") { dialog, _ ->
                val password = passwordEditText.text.toString()
                if (password.isNotEmpty() && selectedImageUri != null) {
                    reauthenticateAndUploadImageOnly(password)
                } else {
                    Toast.makeText(this, "Password is required, and an image must be selected.", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.cancel()
            }
            .create()
            .show()
    }

    private fun reauthenticateAndUploadImageOnly(password: String) {
        val user = auth.currentUser ?: return
        val credential = EmailAuthProvider.getCredential(user.email!!, password)

        user.reauthenticate(credential)
            .addOnSuccessListener {
                selectedImageUri?.let { uploadImageOnly(it) }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Authentication failed. Please check your password.", Toast.LENGTH_LONG).show()
            }
    }
}
