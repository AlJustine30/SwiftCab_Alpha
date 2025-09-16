package com.btsi.swiftcabalpha

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial

class DriverDashboardActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var textViewDriverWelcome: TextView
    private lateinit var navView: NavigationView // Declare navView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_driver_dashboard)

        val toolbar: Toolbar = findViewById(R.id.toolbar_driver_dashboard)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawer_layout_driver_dashboard)
        navView = findViewById(R.id.nav_view_driver) // Initialize navView

        textViewDriverWelcome = findViewById(R.id.textViewDriverWelcome)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open, // Make sure these are in strings.xml
            R.string.navigation_drawer_close // Make sure these are in strings.xml
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navView.setNavigationItemSelectedListener(this)

        // --- Fetch and Display Driver Name ---
        // Replace this with your actual logic to get the driver's name
        // For example, from Firebase Authentication or SharedPreferences
        val driverName = "Jane Doe" // Placeholder, replace with actual name
        textViewDriverWelcome.text = "Welcome, $driverName"

        // Update nav header with driver's name (if you have TextViews for it in nav_header_driver.xml)
        val headerView = navView.getHeaderView(0)
        // Example: If you have a TextView with id textViewNavDriverName in nav_header_driver.xml
        // val textViewNavDriverName: TextView? = headerView.findViewById(R.id.textViewNavDriverName)
        // textViewNavDriverName?.text = driverName
        // Example: If you have a TextView with id textViewNavDriverEmail in nav_header_driver.xml
        // val userEmail = "jane.doe@example.com" // Placeholder, replace with actual email
        // val textViewNavDriverEmail: TextView? = headerView.findViewById(R.id.textViewNavDriverEmail)
        // textViewNavDriverEmail?.text = userEmail


        // --- Driver Status Switch Logic ---
        val driverStatusSwitch: SwitchMaterial = findViewById(R.id.switch_driver_status)
        driverStatusSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                driverStatusSwitch.text = "Online"
                // TODO: Handle logic for driver going online (e.g., update backend)
                Toast.makeText(this, "You are now Online", Toast.LENGTH_SHORT).show()
            } else {
                driverStatusSwitch.text = "Offline"
                // TODO: Handle logic for driver going offline
                Toast.makeText(this, "You are now Offline", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_driver_dashboard_home -> {
                // Already on dashboard, or reload/refresh if needed
                Toast.makeText(this, "Dashboard", Toast.LENGTH_SHORT).show()
            }
            R.id.nav_driver_profile -> {
                // TODO: Intent to DriverProfileActivity
                Toast.makeText(this, "Profile Clicked", Toast.LENGTH_SHORT).show()
            }
            R.id.nav_driver_ride_history -> {
                // TODO: Intent to DriverRideHistoryActivity
                Toast.makeText(this, "Ride History Clicked", Toast.LENGTH_SHORT).show()
            }
            R.id.nav_driver_earnings -> {
                 // TODO: Intent to DriverEarningsActivity
                Toast.makeText(this, "Earnings Clicked", Toast.LENGTH_SHORT).show()
            }
            R.id.nav_driver_settings -> {
                // TODO: Intent to DriverSettingsActivity
                Toast.makeText(this, "Settings Clicked", Toast.LENGTH_SHORT).show()
            }
            R.id.nav_driver_logout -> {
                // Firebase Sign Out
                com.google.firebase.auth.FirebaseAuth.getInstance().signOut()

                // Navigate to LoginActivity
                val intent = android.content.Intent(this, LoginActivity::class.java)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish() // Finish DriverDashboardActivity
                Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
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
