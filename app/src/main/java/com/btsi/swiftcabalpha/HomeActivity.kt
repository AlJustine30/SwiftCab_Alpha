package com.btsi.swiftcabalpha

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem // Kept for onOptionsItemSelected
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar // Ensure this import is present
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class HomeActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle
    private lateinit var toolbar: Toolbar // Declaration is fine

    // Views in Navigation Drawer Header
    private lateinit var headerProfileImage: ImageView
    private lateinit var headerUserName: TextView
    private lateinit var headerEditProfileButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // *** FIX APPLIED HERE: Initialize toolbar before using it ***
        toolbar = findViewById(R.id.toolbar_home)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawer_layout_home)
        navView = findViewById(R.id.nav_view_home)

        toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar, // Use the initialized toolbar
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)

        // Access header views
        val headerView = navView.getHeaderView(0)
        headerProfileImage = headerView.findViewById(R.id.nav_header_profile_image)
        headerUserName = headerView.findViewById(R.id.nav_header_user_name)
        headerEditProfileButton = headerView.findViewById(R.id.nav_header_edit_profile_button)

        headerEditProfileButton.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
            drawerLayout.closeDrawers() // Close drawer after click
        }

        // Assuming these TextViews are in your activity_home.xml for main content display
        val userNameTextView = findViewById<TextView>(R.id.userNameTextView)
        val userEmailTextView = findViewById<TextView>(R.id.userEmailTextView)

        loadUserDataInDrawerAndContent(userNameTextView, userEmailTextView)

        val bookNowButton = findViewById<Button>(R.id.BookNowButton)
        bookNowButton.setOnClickListener {
            Toast.makeText(this, "Book Now clicked!", Toast.LENGTH_SHORT).show()
            // Consider starting a new Activity or showing a dialog here
            // Example: startActivity(Intent(this, BookingActivity::class.java))
        }
    }

    private fun loadUserDataInDrawerAndContent(
        userNameTextViewInContent: TextView,
        userEmailTextViewInContent: TextView
    ) {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            // For main content
            userEmailTextViewInContent.text = "User Email: ${currentUser.email}" // Or format as desired

            db.collection("users").document(currentUser.uid).get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                        val fullName = document.getString("fullName")
                        // Set name in Drawer
                        headerUserName.text = fullName ?: "User Name"
                        // Set name in main content
                        userNameTextViewInContent.text = "Welcome, ${fullName ?: "User"}"

                        // TODO: Load profile image URL from Firestore into headerProfileImage using Glide/Coil
                        // val profileImageUrl = document.getString("profileImageUrl")
                        // if (profileImageUrl != null && profileImageUrl.isNotEmpty()) {
                        //    Glide.with(this).load(profileImageUrl).into(headerProfileImage)
                        // } else {
                        //    headerProfileImage.setImageResource(R.drawable.ic_default_profile) // Example default
                        // }
                    } else {
                        headerUserName.text = "User Name"
                        userNameTextViewInContent.text = "Welcome, User"
                        Toast.makeText(this, "User data not found in Firestore", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { exception ->
                    headerUserName.text = "User Name"
                    userNameTextViewInContent.text = "Welcome, User"
                    Toast.makeText(
                        this,
                        "Error fetching user data: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
        } else {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (toggle.onOptionsItemSelected(item)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(navView)) {
            drawerLayout.closeDrawer(navView)
        } else {
            super.onBackPressed()
        }
    }
}
