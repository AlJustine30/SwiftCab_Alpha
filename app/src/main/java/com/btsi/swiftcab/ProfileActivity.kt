package com.btsi.swiftcab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ProfileActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private val NOTIFICATION_CHANNEL_ID = "profile_update_channel"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        createNotificationChannel()

        val userNameEditText = findViewById<TextInputEditText>(R.id.profileFullNameEditText)
        val userEmailTextView = findViewById<TextView>(R.id.profileEmailTextView)
        val userPhoneEditText = findViewById<TextInputEditText>(R.id.profileMobileEditText)
        val userImageView = findViewById<ImageView>(R.id.profileImageView)
        val logoutButton = findViewById<Button>(R.id.logoutButton)
        val saveProfileButton = findViewById<Button>(R.id.saveProfileButton)
        val changePasswordButton = findViewById<Button>(R.id.changePasswordButton)

        val userId = auth.currentUser?.uid
        userId?.let {
            db.collection("users").document(it).get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                        val name = document.getString("name")
                        val email = document.getString("email")
                        val phone = document.getString("phone")
                        val profileImageUrl = document.getString("profileImageUrl")

                        userNameEditText.setText(name)
                        userEmailTextView.text = email
                        userPhoneEditText.setText(phone)

                        profileImageUrl?.let { url ->
                            Glide.with(this).load(url).into(userImageView)
                        }
                    } else {
                        Toast.makeText(this, "User data not found.", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error getting user data: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }

        saveProfileButton.setOnClickListener {
            val newName = userNameEditText.text.toString().trim()
            val newPhone = userPhoneEditText.text.toString().trim()

            if (newName.isNotEmpty() && newPhone.isNotEmpty()) {
                showPasswordConfirmDialog(newName, newPhone)
            } else {
                Toast.makeText(this, "Name and phone cannot be empty.", Toast.LENGTH_SHORT).show()
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

    private fun showPasswordConfirmDialog(newName: String, newPhone: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_password_confirm, null)
        val passwordEditText = dialogView.findViewById<EditText>(R.id.passwordEditText)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Confirm") { dialog, _ ->
                val password = passwordEditText.text.toString()
                if (password.isNotEmpty()) {
                    reauthenticateAndSaveChanges(password, newName, newPhone)
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

    private fun reauthenticateAndSaveChanges(password: String, newName: String, newPhone: String) {
        val user = auth.currentUser
        val credential = EmailAuthProvider.getCredential(user?.email!!, password)

        user.reauthenticate(credential)
            .addOnSuccessListener {
                updateUserProfile(newName, newPhone)
            }
            .addOnFailureListener {
                Toast.makeText(this, "Authentication failed. Please check your password.", Toast.LENGTH_LONG).show()
            }
    }

    private fun updateUserProfile(newName: String, newPhone: String) {
        val userId = auth.currentUser?.uid!!
        val userUpdates = mapOf(
            "name" to newName,
            "phone" to newPhone
        )

        db.collection("users").document(userId).update(userUpdates)
            .addOnSuccessListener {
                Toast.makeText(this, "Profile updated successfully. Please log in again.", Toast.LENGTH_LONG).show()
                showReloginNotification()

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

    private fun showReloginNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.swiftcab) // Make sure you have this drawable
            .setContentTitle("Profile Updated")
            .setContentText("Please re-login to see changes reflected across the app.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(1, builder.build())
    }
}
