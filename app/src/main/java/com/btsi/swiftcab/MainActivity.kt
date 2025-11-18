package com.btsi.swiftcab

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    /**
     * Sets up entry screen with navigation to Login and Register flows.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val buttonLogin = findViewById<Button>(R.id.buttonLogin)
        val buttonRegister = findViewById<Button>(R.id.ButtonRegister)

        buttonLogin.setOnClickListener {
            Toast.makeText(this, "You are logging in", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }

        buttonRegister.setOnClickListener {
            Toast.makeText(this, "You are registering", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
        }
    }
}
