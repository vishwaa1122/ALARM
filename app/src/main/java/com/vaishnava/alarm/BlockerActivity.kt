package com.vaishnava.alarm

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class BlockerActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen blocking overlay
        val view = View(this).apply {
            setBackgroundColor(Color.BLACK)          // solid black
            isClickable = true                        // consume touches
            isFocusable = true
            setOnTouchListener { _, _ -> true }       // eat all touch events
        }
        setContentView(view)

        // Auto-finish after 2 seconds and return to AlarmActivity
        handler.postDelayed({
            finish()
            startActivity(
                Intent(this, AlarmActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
        }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null) // clean up handler
    }
}
                        
