package com.btsi.swiftcab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions

@Suppress("UNCHECKED_CAST")
class BookingViewModelFactory(
    private val database: FirebaseDatabase,
    private val auth: FirebaseAuth,
    private val functions: FirebaseFunctions,
    private val firestore: FirebaseFirestore
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BookingViewModel::class.java)) {
            return BookingViewModel(auth, database, functions, firestore) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
