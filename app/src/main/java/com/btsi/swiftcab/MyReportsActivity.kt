package com.btsi.swiftcab

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.btsi.swiftcab.models.Report
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class MyReportsActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var adapter: ReportsAdapter

    private lateinit var toolbar: Toolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var summaryText: TextView

    /**
     * Initializes toolbar, recycler, adapter and loads current user's reports.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_reports)

        toolbar = findViewById(R.id.toolbarMyReports)
        recyclerView = findViewById(R.id.recyclerViewReports)
        progressBar = findViewById(R.id.progressBar)
        summaryText = findViewById(R.id.textViewSummary)

        setSupportActionBar(toolbar)
        supportActionBar?.title = "My Reports"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        adapter = ReportsAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadMyReports()
    }

    /**
     * Loads all reports submitted by the current user and resolves driver names.
     */
    private fun loadMyReports() {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrEmpty()) {
            Toast.makeText(this, "Not signed in.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        progressBar.visibility = View.VISIBLE
        firestore.collection("reports")
            .whereEqualTo("reporterId", uid)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                val items = snap.documents.mapNotNull { it.toObject(Report::class.java) }
                adapter.submitList(items)
                summaryText.text = "You have submitted ${items.size} reports"
                resolveDriverNames(items)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to load reports: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            .addOnCompleteListener { progressBar.visibility = View.GONE }
    }

    /**
     * Looks up driver names for the given reports and updates adapter metadata.
     */
    private fun resolveDriverNames(items: List<Report>) {
        val ids = items.map { it.driverId }.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) {
            adapter.setDriverNames(emptyMap())
            return
        }
        val nameMap = mutableMapOf<String, String>()
        var remaining = ids.size
        ids.forEach { id ->
            firestore.collection("drivers").document(id).get()
                .addOnSuccessListener { doc ->
                    val name = doc.getString("name") ?: doc.getString("displayName")
                    if (!name.isNullOrBlank()) {
                        nameMap[id] = name
                    }
                }
                .addOnCompleteListener {
                    remaining -= 1
                    if (remaining <= 0) {
                        adapter.setDriverNames(nameMap)
                    }
                }
        }
    }

    /**
     * Handles toolbar up navigation.
     */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
