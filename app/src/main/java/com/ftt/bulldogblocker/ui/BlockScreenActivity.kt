package com.ftt.bulldogblocker.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen block overlay.
 * MUST extend AppCompatActivity — MaterialComponents theme requires it.
 */
class BlockScreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val reason = intent.getStringExtra("reason") ?: "🚫 কন্টেন্ট ব্লক করা হয়েছে"

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

        val tvIcon = TextView(this).apply {
            text     = "🐶"
            textSize = 72f
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvTitle = TextView(this).apply {
            text      = "ব্লক করা হয়েছে"
            textSize  = 28f
            setTextColor(Color.WHITE)
            gravity   = Gravity.CENTER
            setPadding(0, 16, 0, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvReason = TextView(this).apply {
            this.text = reason
            textSize  = 16f
            setTextColor(Color.parseColor("#FFCDD2"))
            gravity   = Gravity.CENTER
            setPadding(0, 0, 0, 48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnBack = Button(this).apply {
            text = "← ফিরে যান"
            setBackgroundColor(Color.parseColor("#7F0000"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setOnClickListener { finish() }
        }

        root.addView(tvIcon)
        root.addView(tvTitle)
        root.addView(tvReason)
        root.addView(btnBack)

        setContentView(root)
    }
}
