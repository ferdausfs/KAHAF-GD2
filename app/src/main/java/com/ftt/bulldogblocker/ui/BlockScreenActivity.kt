package com.ftt.bulldogblocker.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen block overlay.
 * FIX: Back button এবং recent apps দিয়ে dismiss করা যাবে না।
 * User কে HOME বাটন দিয়ে যেতে হবে — এবং service আবার block করবে যদি ফিরে আসে।
 */
class BlockScreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val reason = intent.getStringExtra("reason") ?: "🚫 কন্টেন্ট ব্লক করা হয়েছে"

        // FIX: Block back press entirely — user cannot dismiss block screen with back
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Silently consume — do nothing
            }
        })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            setPadding(48, 0, 48, 0)
            setBackgroundColor(Color.parseColor("#B71C1C"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(TextView(this).apply {
            text     = "🐶"
            textSize = 72f
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        root.addView(TextView(this).apply {
            text      = "ব্লক করা হয়েছে"
            textSize  = 28f
            setTextColor(Color.WHITE)
            gravity   = Gravity.CENTER
            setPadding(0, 16, 0, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        root.addView(TextView(this).apply {
            this.text = reason
            textSize  = 16f
            setTextColor(Color.parseColor("#FFCDD2"))
            gravity   = Gravity.CENTER
            setPadding(0, 0, 0, 48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        // FIX: "ফিরে যান" button সরিয়ে দেওয়া হয়েছে।
        // User শুধু Home button দিয়ে যেতে পারবে।
        root.addView(TextView(this).apply {
            text     = "Home বাটন চাপুন"
            textSize = 14f
            setTextColor(Color.parseColor("#EF9A9A"))
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        setContentView(root)
    }

    // FIX: Hardware back key ও block করা হচ্ছে (volume/power key ছাড়া সব)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> true  // block
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
