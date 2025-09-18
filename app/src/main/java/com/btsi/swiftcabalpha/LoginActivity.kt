package com.btsi.swiftcabalpha

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
import com.btsi.swiftcabalpha.DriverDashboardActivity // Added import

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
                        firebaseUser?.let { user ->
                            val userId = user.uid

                            // First, check if the user is in the \'users\' (Passenger) collection
                            db.collection("users").document(userId).get()
                                .addOnSuccessListener { passengerDocument ->
                                    if (passengerDocument.exists()) {
                                        // User found in \'users\' collection, treat as Passenger
                                        progressBar.visibility = android.view.View.GONE
                                        loginButton.isEnabled = true
                                        Toast.makeText(this, "Passenger Login successful", Toast.LENGTH_SHORT).show()
                                        val intent = Intent(this, HomeActivity::class.java)
                                        startActivity(intent)
                                        finish()
                                    } else {
                                        // User not found in \'users\', check \'drivers\' (Driver) collection
                                        db.collection("drivers").document(userId).get()
                                            .addOnSuccessListener { driverDocument ->
                                                progressBar.visibility = android.view.View.GONE
                                                loginButton.isEnabled = true
                                                if (driverDocument.exists()) {
                                                    // User found in \'drivers\' collection, treat as Driver
                                                    Toast.makeText(this, "Driver Login successful", Toast.LENGTH_SHORT).show()
                                                    val intent = Intent(this, DriverDashboardActivity::class.java)
                                                    startActivity(intent)
                                                    finish()
                                                } else {
                                                    // User not found in \'users\' or \'drivers\'
                                                    Toast.makeText(this, "User data not found. Please register or contact support.", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                            .addOnFailureListener { e_driver ->
                                                // Failed to check \'drivers\' collection
                                                progressBar.visibility = android.view.View.GONE
                                                loginButton.isEnabled = true
                                                Toast.makeText(this, "Error checking driver data: ${e_driver.message}", Toast.LENGTH_LONG).show()
                                            }
                                    }
                                }
                                .addOnFailureListener { e_passenger ->
                                    // Failed to check \'users\' collection, but still try \'drivers\' as a fallback
                                    // You might want to log e_passenger.message or handle it differently
                                    db.collection("drivers").document(userId).get()
                                        .addOnSuccessListener { driverDocument ->
                                            progressBar.visibility = android.view.View.GONE
                                            loginButton.isEnabled = true
                                            if (driverDocument.exists()) {
                                                Toast.makeText(this, "Driver Login successful (after user check error)", Toast.LENGTH_SHORT).show()
                                                val intent = Intent(this, DriverDashboardActivity::class.java)
                                                startActivity(intent)
                                                finish()
                                            } else {
                                                Toast.makeText(this, "Error checking user data and user not found as driver. ${e_passenger.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                        .addOnFailureListener { e_driver_fallback ->
                                             progressBar.visibility = android.view.View.GONE
                                             loginButton.isEnabled = true
                                             Toast.makeText(this, "Error checking both user and driver data: ${e_driver_fallback.message}", Toast.LENGTH_LONG).show()
                                        }
                                }
                        } ?: run {
                            // Should not happen if task.isSuccessful is true and firebaseUser is not null
                            progressBar.visibility = android.view.View.GONE
                            loginButton.isEnabled = true
                            Toast.makeText(this, "Login successful, but user data is unexpectedly null.", Toast.LENGTH_LONG).show()
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
