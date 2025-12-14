package com.vaishnava.alarm

import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SearchReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SearchReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "SearchReceiver received intent: $intent")
        
        // Handle search intent
        if (intent?.action == Intent.ACTION_SEARCH) {
            val query = intent.getStringExtra(SearchManager.QUERY)
            Log.d(TAG, "Handling search intent with query: $query")
            
            // Launch the main activity
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                // Pass the search query to the activity
                putExtra(SearchManager.QUERY, query)
            }
            
            try {
                context?.startActivity(launchIntent)
                Log.d(TAG, "Launched MainActivity with search query: $query")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch MainActivity: ${e.message}", e)
            }
        }
    }
}
