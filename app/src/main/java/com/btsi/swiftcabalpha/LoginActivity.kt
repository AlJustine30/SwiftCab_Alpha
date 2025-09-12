package com.btsi.swiftcabalpha

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore // Added Firestore import
import com.github.ybq.android.spinkit.SpinKitView

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore // Added Firestore instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance() // Initialize Firestore

        val emailEditText = findViewById<TextInputEditText>(R.id.emailEditText)
        val passwordEditText = findViewById<TextInputEditText>(R.id.passwordEditText)
        val loginButton = findViewById<Button>(R.id.loginButton)
        val registerRedirectTextView = findViewById<TextView>(R.id.registerRedirectTextView)
        val backToWelcomeTextView = findViewById<TextView>(R.id.backToWelcomeTextView)
        val progressBar = findViewById<SpinKitView>(R.id.progressBar)

        progressBar.visibility = android.view.View.GONE

        loginButton.setOnClickListener {
            val email = emailEditText.text.toString().trim()
            val password = passwordEditText.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            progressBar.visibility = android.view.View.VISIBLE
            loginButton.isEnabled = false

            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        val firebaseUser = auth.currentUser
                        firebaseUser?.let { user ->
                            val userId = user.uid
                            db.collection("users").document(userId).get()
                                .addOnSuccessListener { documentSnapshot ->
                                    progressBar.visibility = android.view.View.GONE
                                    loginButton.isEnabled = true
                                    if (documentSnapshot.exists()) {
                                        val role = documentSnapshot.getString("role")
                                        when (role) {
                                            "Driver" -> {
                                                Toast.makeText(this, "Driver Login successful", Toast.LENGTH_SHORT).show()
                                                val intent = Intent(this, DriverDashboardActivity::class.java)
                                                startActivity(intent)
                                                finish()
                                            }
                                            "Passenger" -> {
                                                Toast.makeText(this, "Passenger Login successful", Toast.LENGTH_SHORT).show()
                                                val intent = Intent(this, HomeActivity::class.java)
                                                startActivity(intent)
                                                finish()
                                            }
                                            else -> {
                                                // Role is null, not 'Driver', or not 'Passenger'
                                                Toast.makeText(this, "User role not defined or unknown.", Toast.LENGTH_LONG).show()
                                                // Optionally, sign out the user if role is critical and not found
                                                // auth.signOut()
                                            }
                                        }
                                    } else {
                                        // User document doesn't exist in Firestore for this UID
                                        Toast.makeText(this, "User data not found. Please contact support.", Toast.LENGTH_LONG).show()
                                        // Optionally, sign out the user
                                        // auth.signOut()
                                    }
                                }
                                .addOnFailureListener { e ->
                                    progressBar.visibility = android.view.View.GONE
                                    loginButton.isEnabled = true
                                    Toast.makeText(this, "Error fetching user role: ${e.message}", Toast.LENGTH_LONG).show()
                                    // Optionally, sign out the user
                                    // auth.signOut()
                                }
                        } ?: run {
                            // Should not happen if task.isSuccessful is true and user is not null
                            progressBar.visibility = android.view.View.GONE
                            loginButton.isEnabled = true
                            Toast.makeText(this, "Login successful, but user data is null.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        progressBar.visibility = android.view.View.GONE
                        loginButton.isEnabled = true
                        Toast.makeText(this, "Authentication failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }

        registerRedirectTextView.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
        }

        backToWelcomeTextView.setOnClickListener {
            finish()
        }
    }
}
