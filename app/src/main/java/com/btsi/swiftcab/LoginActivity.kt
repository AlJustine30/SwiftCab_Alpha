package com.btsi.swiftcab

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.github.ybq.android.spinkit.SpinKitView


class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

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
                        if (firebaseUser == null) {
                            progressBar.visibility = android.view.View.GONE
                            loginButton.isEnabled = true
                            Toast.makeText(this, "Login successful, but user is null.", Toast.LENGTH_LONG).show()
                            return@addOnCompleteListener
                        }

                        val userId = firebaseUser.uid

                        // First, check if the user is a driver. Drivers bypass email verification.
                        db.collection("drivers").document(userId).get()
                            .addOnSuccessListener { driverDocument ->
                                if (driverDocument.exists()) {
                                    // User is a driver, log them in directly.
                                    progressBar.visibility = android.view.View.GONE
                                    loginButton.isEnabled = true
                                    Toast.makeText(this, "Driver Login successful", Toast.LENGTH_SHORT).show()
                                    val intent = Intent(this, DriverDashboardActivity::class.java)
                                    startActivity(intent)
                                    finish()
                                } else {
                                    // Not a driver, so must be a passenger. Check for passenger record and verify email.
                                    db.collection("users").document(userId).get()
                                        .addOnSuccessListener { passengerDocument ->
                                            if (passengerDocument.exists()) {
                                                // It's a passenger, so we MUST check for email verification.
                                                if (firebaseUser.isEmailVerified) {
                                                    progressBar.visibility = android.view.View.GONE
                                                    loginButton.isEnabled = true
                                                    Toast.makeText(this, "Passenger Login successful", Toast.LENGTH_SHORT).show()
                                                    val intent = Intent(this, HomeActivity::class.java)
                                                    startActivity(intent)
                                                    finish()
                                                } else {
                                                    // Passenger's email is not verified.
                                                    progressBar.visibility = android.view.View.GONE
                                                    loginButton.isEnabled = true
                                                    Toast.makeText(this, "Please verify your email address before logging in.", Toast.LENGTH_LONG).show()
                                                    auth.signOut() // Sign out the unverified user.
                                                }
                                            } else {
                                                // User exists in Auth but not in 'drivers' or 'users' collections.
                                                progressBar.visibility = android.view.View.GONE
                                                loginButton.isEnabled = true
                                                Toast.makeText(this, "User data not found. Please register or contact support.", Toast.LENGTH_LONG).show()
                                                auth.signOut()
                                            }
                                        }
                                        .addOnFailureListener { e_passenger ->
                                            progressBar.visibility = android.view.View.GONE
                                            loginButton.isEnabled = true
                                            Toast.makeText(this, "Error checking user data: ${e_passenger.message}", Toast.LENGTH_LONG).show()
                                            auth.signOut()
                                        }
                                }
                            }
                            .addOnFailureListener { e_driver ->
                                progressBar.visibility = android.view.View.GONE
                                loginButton.isEnabled = true
                                Toast.makeText(this, "Error checking driver data: ${e_driver.message}", Toast.LENGTH_LONG).show()
                                auth.signOut()
                            }
                    } else {
                        // Authentication failed
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
            finish() // Goes back to the previous activity
        }
    }
}
