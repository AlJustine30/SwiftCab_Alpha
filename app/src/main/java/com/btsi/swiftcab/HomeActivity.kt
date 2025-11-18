package com.btsi.swiftcab

import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import com.google.firebase.firestore.ListenerRegistration

class HomeActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private companion object {
        private const val TAG = "HomeActivity"
    }

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var userListener: ListenerRegistration? = null

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle
    private lateinit var toolbar: Toolbar

    // Views in Navigation Drawer Header
    private lateinit var headerProfileImage: ImageView
    private lateinit var headerUserName: TextView
    private lateinit var headerEditProfileButton: Button

    // Views in main content
    private lateinit var userNameTextView: TextView
    private lateinit var userEmailTextView: TextView

    /**
     * Initializes toolbar, drawer, header, buttons, and user data listener.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        toolbar = findViewById(R.id.toolbar_home)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawer_layout_home)
        navView = findViewById(R.id.nav_view_home)
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

        // Access header views
        val headerView = navView.getHeaderView(0)
        headerProfileImage = headerView.findViewById(R.id.nav_header_profile_image)
        headerUserName = headerView.findViewById(R.id.nav_header_user_name)
        headerEditProfileButton = headerView.findViewById(R.id.nav_header_edit_profile_button)

        headerEditProfileButton.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
            drawerLayout.closeDrawers()
        }

        userNameTextView = findViewById(R.id.userNameTextView)
        userEmailTextView = findViewById(R.id.userEmailTextView)

        setupUserDataListener()

        val bookNowButton = findViewById<Button>(R.id.BookNowButton)
        val returnToBookingButton = findViewById<Button>(R.id.ReturnToBookingButton)
        bookNowButton.setOnClickListener {
            startActivity(Intent(this, BookingActivity::class.java))
        }
        returnToBookingButton.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Resume ongoing trip")
                .setMessage("You have an ongoing trip. Do you want to resume now?")
                .setPositiveButton("Resume") { dialog, _ ->
                    startActivity(Intent(this, BookingActivity::class.java))
                    dialog.dismiss()
                }
                .setNegativeButton("Stay here") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    /**
     * Checks for an ongoing booking and prompts to resume if found.
     */
    override fun onResume() {
        super.onResume()
        // Check for ongoing booking and toggle button visibility; prompt to resume
        val uid = auth.currentUser?.uid ?: return
        val ref = com.google.firebase.database.FirebaseDatabase.getInstance().getReference("bookingRequests")
        ref.orderByChild("riderId").equalTo(uid).get().addOnSuccessListener { snapshot ->
            var hasOngoing = false
            snapshot.children.forEach { child ->
                val req = child.getValue(com.btsi.swiftcab.models.BookingRequest::class.java)
                val status = req?.status
                if (status != null && status !in listOf("COMPLETED", "CANCELED", "NO_DRIVERS", "ERROR")) {
                    hasOngoing = true
                }
            }
            val btn = findViewById<Button>(R.id.ReturnToBookingButton)
            if (hasOngoing) {
                btn.visibility = android.view.View.VISIBLE
                // Ask for confirmation instead of auto-opening
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Resume ongoing trip")
                    .setMessage("You have an ongoing trip. Do you want to resume now?")
                    .setPositiveButton("Resume") { dialog, _ ->
                        startActivity(Intent(this, BookingActivity::class.java))
                        dialog.dismiss()
                    }
                    .setNegativeButton("Not now") { dialog, _ -> dialog.dismiss() }
                    .show()
            } else {
                btn.visibility = android.view.View.GONE
            }
        }
    }

    /**
     * Subscribes to Firestore user document and updates header/profile UI.
     */
    private fun setupUserDataListener() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        userEmailTextView.text = "User Email: ${currentUser.email}"

        userListener = db.collection("users").document(currentUser.uid)
            .addSnapshotListener(this) { snapshot, e ->
                if (e != null) {
                    Log.w(TAG, "Listen failed.", e)
                    Toast.makeText(this, "Error fetching user data: ${e.message}", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    Log.d(TAG, "User data updated.")
                    // Use "name" to match ProfileActivity, not "fullName"
                    val name = snapshot.getString("name")
                    headerUserName.text = name ?: "User Name"
                    userNameTextView.text = "Welcome, ${name ?: "User"}"

                    val profileImageUrl = snapshot.getString("profileImageUrl")
                    Glide.with(this@HomeActivity)
                        .load(profileImageUrl)
                        .placeholder(R.drawable.default_profile)
                        .into(headerProfileImage)
                } else {
                    Log.d(TAG, "Current data: null")
                }
            }
    }

    /**
     * Delegates toolbar toggle handling for the drawer.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (toggle.onOptionsItemSelected(item)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Handles navigation drawer selections and routes to target screens.
     */
    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_booking_history -> {
                startActivity(Intent(this, BookingHistoryActivity::class.java))
            }
            R.id.nav_loyalty -> {
                startActivity(Intent(this, LoyaltyActivity::class.java))
            }
            R.id.nav_rider_ratings -> {
                startActivity(Intent(this, RiderRatingsActivity::class.java))
            }
            R.id.nav_my_reports -> {
                startActivity(Intent(this, MyReportsActivity::class.java))
            }
            R.id.nav_logout -> {
                auth.signOut()
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            else -> {
                Toast.makeText(this, "Unknown navigation item: ${item.title}", Toast.LENGTH_SHORT).show()
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    /**
     * Closes the drawer on back press when open; otherwise delegates.
     */
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    /**
     * Removes Firestore listeners to prevent leaks.
     */
    override fun onDestroy() {
        super.onDestroy()
        // Remove the listener to prevent memory leaks
        userListener?.remove()
    }
}
