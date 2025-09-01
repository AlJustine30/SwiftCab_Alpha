package com.btsi.swiftcabalpha
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.github.ybq.android.spinkit.SpinKitView

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()

        val emailEditText = findViewById<TextInputEditText>(R.id.emailEditText)
        val passwordEditText = findViewById<TextInputEditText>(R.id.passwordEditText)
        val loginButton = findViewById<Button>(R.id.loginButton)
        val registerRedirectTextView = findViewById<TextView>(R.id.registerRedirectTextView)
        val backToWelcomeTextView = findViewById<TextView>(R.id.backToWelcomeTextView)
        val progressBar = findViewById<SpinKitView>(R.id.progressBar)

        // Hide progress bar initially
        progressBar.visibility = android.view.View.GONE

        loginButton.setOnClickListener {
            val email = emailEditText.text.toString().trim()
            val password = passwordEditText.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Show progress bar
            progressBar.visibility = android.view.View.VISIBLE
            loginButton.isEnabled = false

            // Authenticate with Firebase
            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        // Login success
                        Toast.makeText(this, "Login successful", Toast.LENGTH_SHORT).show()

                        // Redirect to home activity
                        val intent = Intent(this, HomeActivity::class.java)
                        startActivity(intent)
                        finish()
                    } else {
                        // Login failed
                        Toast.makeText(this, "Authentication failed: ${task.exception?.message}",
                            Toast.LENGTH_SHORT).show()
                    }

                    // Hide progress bar and re-enable button
                    progressBar.visibility = android.view.View.GONE
                    loginButton.isEnabled = true
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