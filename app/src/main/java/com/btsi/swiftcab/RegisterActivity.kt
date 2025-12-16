package com.btsi.swiftcab

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.widget.ScrollView
import android.widget.LinearLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.github.ybq.android.spinkit.SpinKitView

class   RegisterActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    /**
     * Initializes registration form, creates account, sends verification email,
     * and stores profile in Firestore before redirecting to login.
     */
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
        val termsCheckBox = findViewById<CheckBox>(R.id.checkboxAgreeTerms)
        val viewTermsTextView = findViewById<TextView>(R.id.textViewViewTerms)

        // Hide progress bar initially
        progressBar.visibility = android.view.View.GONE

        viewTermsTextView.setOnClickListener {
            showTermsDialog {
                termsCheckBox.isChecked = true
            }
        }

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

            if (!termsCheckBox.isChecked) {
                Toast.makeText(this, "Please read and agree to the Terms and the Data Privacy Act of 2012", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Show progress bar
            progressBar.visibility = android.view.View.VISIBLE
            registerButton.isEnabled = false

            // Create user with Firebase Auth
            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        // Registration success, get the user
                        val user = auth.currentUser
                        // Send verification email
                        user?.sendEmailVerification()?.addOnCompleteListener { verificationTask ->
                            if (verificationTask.isSuccessful) {
                                // Store additional user data in Firestore
                                val userData = hashMapOf(
                                    "name" to fullName,
                                    "email" to email,
                                    "phone" to phone,
                                    "role" to "Passenger",
                                    "createdAt" to System.currentTimeMillis()
                                )

                                db.collection("users")
                                    .document(user.uid)
                                    .set(userData)
                                    .addOnSuccessListener {
                                        Toast.makeText(this, "Registration successful. Please check your email for verification.", Toast.LENGTH_LONG).show()
                                        // Sign out to force login after verification
                                        auth.signOut()
                                        // Redirect to login activity
                                        val intent = Intent(this, LoginActivity::class.java)
                                        startActivity(intent)
                                        finish()
                                    }
                                    .addOnFailureListener { e ->
                                        Toast.makeText(this, "Error saving user data: ${e.message}", Toast.LENGTH_SHORT).show()
                                        progressBar.visibility = android.view.View.GONE
                                        registerButton.isEnabled = true
                                    }
                            } else {
                                Toast.makeText(this, "Failed to send verification email: ${verificationTask.exception?.message}", Toast.LENGTH_LONG).show()
                                progressBar.visibility = android.view.View.GONE
                                registerButton.isEnabled = true
                            }
                        }
                    } else {
                        // Registration failed
                        Toast.makeText(this, "Registration failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
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

    private fun showTermsDialog(onAgree: () -> Unit) {
        val content = """
SwiftCab Terms and Conditions

By creating an account, you confirm that the information you provide is accurate and that you will use the service lawfully. You agree not to misuse the app, interfere with operations, or engage in fraudulent activity. Trips, fares, and promotions are subject to change. We may contact you for service updates, safety notifications, or support. Violations may result in suspension or termination of your account.

Data Privacy Act of 2012 (Republic Act No. 10173)

SwiftCab processes personal data in accordance with RA 10173. The personal information we collect may include your name, email, phone number, location data, device information, trip details, and ratings. We process this data to:
• create and manage your account
• match riders and drivers and operate trips
• ensure safety, prevent fraud, and enforce policies
• support payments, customer support, and service improvements
• comply with legal obligations

Legal bases include your consent, performance of a contract, legitimate interests, and compliance with law. You have rights to access, correct, and in certain cases delete your personal data; to object or withdraw consent; and to file a complaint with the National Privacy Commission (NPC).

We retain data only for as long as necessary for the purposes stated or as required by law. We may share data with drivers, payment processors, cloud and analytics providers, and service partners under appropriate safeguards. We implement reasonable and appropriate security measures to protect your data.

By agreeing, you consent to the collection and processing of your personal data as described.
        """.trimIndent()

        val scroll = ScrollView(this)
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(48, 32, 48, 32)
        val tv = TextView(this)
        tv.text = content
        tv.textSize = 14f
        container.addView(tv)
        scroll.addView(container)

        AlertDialog.Builder(this)
            .setTitle("Terms & Data Privacy Act of 2012")
            .setView(scroll)
            .setPositiveButton("I Agree") { dialog, _ ->
                onAgree()
                dialog.dismiss()
            }
            .setNegativeButton("Close") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
