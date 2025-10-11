package com.btsi.SwiftCab

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.bumptech.glide.Glide
import com.google.android.material.navigation.NavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class HomeActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle
    private lateinit var toolbar: Toolbar

    // Views in Navigation Drawer Header
    private lateinit var headerProfileImage: ImageView
    private lateinit var headerUserName: TextView
    private lateinit var headerEditProfileButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        toolbar = findViewById(R.id.toolbar_home)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawer_layout_home)
        navView = findViewById(R.id.nav_view_home)

        // Set navigation item selected listener
        navView.setNavigationItemSelectedListener(this)

        toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
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
            drawerLayout.closeDrawers() // Closes drawer after click
        }

        val userNameTextView = findViewById<TextView>(R.id.userNameTextView)
        val userEmailTextView = findViewById<TextView>(R.id.userEmailTextView)

        loadUserDataInDrawerAndContent(userNameTextView, userEmailTextView)

        val bookNowButton = findViewById<Button>(R.id.BookNowButton)
        bookNowButton.setOnClickListener {
            startActivity(Intent(this, BookingActivity::class.java))
        }
    }

    private fun loadUserDataInDrawerAndContent(
        userNameTextViewInContent: TextView,
        userEmailTextViewInContent: TextView
    ) {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            userEmailTextViewInContent.text = "User Email: ${currentUser.email}"

            db.collection("users").document(currentUser.uid).get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                        val fullName = document.getString("fullName")
                        headerUserName.text = fullName ?: "User Name"
                        userNameTextViewInContent.text = "Welcome, ${fullName ?: "User"}"

                        val profileImageUrl = document.getString("profileImageUrl")
                        if (profileImageUrl != null && profileImageUrl.isNotEmpty()) {
                            Glide.with(this@HomeActivity)
                                .load(profileImageUrl)
                                .placeholder(R.drawable.default_profile)
                                .error(R.drawable.default_profile)
                                .circleCrop()
                                .into(headerProfileImage)
                        } else {
                            headerProfileImage.setImageResource(R.drawable.default_profile)
                        }
                    } else {
                        headerUserName.text = "User Name"
                        userNameTextViewInContent.text = "Welcome, User"
                        headerProfileImage.setImageResource(R.drawable.default_profile)
                        Toast.makeText(this, "User data not found in Firestore", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { exception ->
                    headerUserName.text = "User Name"
                    userNameTextViewInContent.text = "Welcome, User"
                    headerProfileImage.setImageResource(R.drawable.swiftcab)
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

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_booking_history -> {
                startActivity(Intent(this, BookingHistoryActivity::class.java))
            }
            R.id.nav_logout -> {
                auth.signOut()
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish() // Finish HomeActivity
            }
            // Add other cases here if need pa add ng menu items
            else -> {
                Toast.makeText(this, "Unknown navigation item: ${item.title}", Toast.LENGTH_SHORT).show() // DEBUG for items
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START) // Close the drawer
        return true
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
