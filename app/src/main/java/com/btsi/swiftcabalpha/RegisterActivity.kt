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

class RegisterActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val fullNameEditText = findViewById<TextInputEditText>(R.id.fullNameEditText)
        val emailEditText = findViewById<TextInputEditText>(R.id.emailEditText)
        val phoneEditText = findViewById<TextInputEditText>(R.id.phoneEditText)
        val passwordEditText = findViewById<TextInputEditText>(R.id.passwordEditText)
        val confirmPasswordEditText = findViewById<TextInputEditText>(R.id.confirmPasswordEditText)
        val registerButton = findViewById<Button>(R.id.registerButton)
        val loginRedirectTextView = findViewById<TextView>(R.id.loginRedirectTextView)
        val backToWelcomeTextView = findViewById<TextView>(R.id.backToWelcomeTextView)
        val progressBar = findViewById<SpinKitView>(R.id.progressBar)

        // Hide progress bar initially
        progressBar.visibility = android.view.View.GONE

        registerButton.setOnClickListener {
            val fullName = fullNameEditText.text.toString().trim()
            val email = emailEditText.text.toString().trim()
            val phone = phoneEditText.text.toString().trim()
            val password = passwordEditText.text.toString().trim()
            val confirmPassword = confirmPasswordEditText.text.toString().trim()

            if (fullName.isEmpty() || email.isEmpty() || phone.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password != confirmPassword) {
                Toast.makeText(this, "Passwords don't match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password.length < 6) {
                Toast.makeText(this, "Password should be at least 6 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Show progress bar
            progressBar.visibility = android.view.View.VISIBLE
            registerButton.isEnabled = false

            // Create user with Firebase Auth
            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        // Registration success
                        val user = auth.currentUser

                        // Store additional user data in Firestore
                        val userData = hashMapOf(
                            "fullName" to fullName,
                            "email" to email,
                            "phone" to phone,
                            "createdAt" to System.currentTimeMillis()
                        )

                        db.collection("users")
                            .document(user!!.uid)
                            .set(userData)
                            .addOnSuccessListener {
                                Toast.makeText(this, "Registration successful", Toast.LENGTH_SHORT).show()

                                // Redirect to home activity
                                val intent = Intent(this, HomeActivity::class.java)
                                startActivity(intent)
                                finish()
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(this, "Error saving user data: ${e.message}",
                                    Toast.LENGTH_SHORT).show()

                                // Hide progress bar and re-enable button
                                progressBar.visibility = android.view.View.GONE
                                registerButton.isEnabled = true
                            }
                    } else {
                        // Registration failed
                        Toast.makeText(this, "Registration failed: ${task.exception?.message}",
                            Toast.LENGTH_SHORT).show()

                        // Hide progress bar and re-enable button
                        progressBar.visibility = android.view.View.GONE
                        registerButton.isEnabled = true
                    }
                }
        }

        loginRedirectTextView.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }

        backToWelcomeTextView.setOnClickListener {
            finish()
        }
    }
}