package com.ftt.bulldogblocker.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen block overlay.
 * Shown when adult content is detected by Accessibility Service or ML classifier.
 */
class BlockScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val reason = intent.getStringExtra("reason") ?: "🚫 কন্টেন্ট ব্লক করা হয়েছে"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = android.view.Gravity.CENTER
            setPadding(48, 0, 48, 0)
            setBackgroundColor(android.graphics.Color.parseColor("#B71C1C"))
        }

        val tvIcon = TextView(this).apply {
            text     = "🐶"
            textSize = 72f
            gravity  = android.view.Gravity.CENTER
        }

        val tvTitle = TextView(this).apply {
            text      = "ব্লক করা হয়েছে"
            textSize  = 28f
            setTextColor(android.graphics.Color.WHITE)
            gravity   = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 8)
        }

        val tvReason = TextView(this).apply {
            text      = reason
            textSize  = 16f
            setTextColor(android.graphics.Color.parseColor("#FFCDD2"))
            gravity   = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        val btnBack = Button(this).apply {
            text = "← ফিরে যান"
            setBackgroundColor(android.graphics.Color.parseColor("#C62828"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener { finish() }
        }

        root.addView(tvIcon)
        root.addView(tvTitle)
        root.addView(tvReason)
        root.addView(btnBack)

        setContentView(root)
    }
}
