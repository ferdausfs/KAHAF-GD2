package com.ftt.bulldogblocker.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.ftt.bulldogblocker.ml.FalsePositiveDB

/**
 * Full-screen block overlay।
 *
 * score < 77% হলে True / False report buttons দেখায়:
 *   ✅ সঠিক   → confirmed true DB-তে hash add হয় (পরে same image-এ report আসবে না)
 *   ❌ ভুল    → false positive DB-তে hash add হয় (পরে same image block হবে না)
 *
 * score ≥ 77% বা confirmed true → শুধু block screen, কোনো button নেই।
 */
class BlockScreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val reason     = intent.getStringExtra("reason")     ?: "🚫 কন্টেন্ট ব্লক করা হয়েছে"
        val imageHash  = intent.getLongExtra("image_hash",   0L)
        val score      = intent.getFloatExtra("score",       1f)
        val showReport = intent.getBooleanExtra("show_report", false)

        // Back press সম্পূর্ণ block
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* block */ }
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

        // ── Dog icon ─────────────────────────────────────────────────
        root.addView(TextView(this).apply {
            text     = "🐶"
            textSize = 72f
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        // ── Title ─────────────────────────────────────────────────────
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

        // ── Reason ────────────────────────────────────────────────────
        root.addView(TextView(this).apply {
            this.text = reason
            textSize  = 16f
            setTextColor(Color.parseColor("#FFCDD2"))
            gravity   = Gravity.CENTER
            setPadding(0, 0, 0, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        if (showReport && imageHash != 0L) {
            // ── Report section (score < 77%) ──────────────────────────
            root.addView(TextView(this).apply {
                text     = "এই block কি সঠিক ছিল?"
                textSize = 15f
                setTextColor(Color.parseColor("#FFECB3"))
                gravity  = Gravity.CENTER
                setPadding(0, 0, 0, 16)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })

            // ✅ সঠিক বাটন
            root.addView(Button(this).apply {
                text = "✅  হ্যাঁ, সঠিক ছিল"
                textSize = 15f
                setBackgroundColor(Color.parseColor("#1B5E20"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
                setOnClickListener {
                    // Confirmed true → পরে same image-এ আর report dialog আসবে না
                    FalsePositiveDB.addTrue(this@BlockScreenActivity, imageHash)
                    finish()
                }
            })

            // ❌ ভুল বাটন
            root.addView(Button(this).apply {
                text = "❌  না, ভুল block (False Positive)"
                textSize = 15f
                setBackgroundColor(Color.parseColor("#37474F"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 24) }
                setOnClickListener {
                    // False positive → পরে similar image block হবে না
                    FalsePositiveDB.addFalse(this@BlockScreenActivity, imageHash)
                    finish()
                }
            })

            // Score label
            val scorePct = (score * 100).toInt()
            root.addView(TextView(this).apply {
                text     = "Model confidence: ${scorePct}%  (threshold-এর নিচে বলে জিজ্ঞেস করছে)"
                textSize = 11f
                setTextColor(Color.parseColor("#EF9A9A"))
                gravity  = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        } else {
            // ── শুধু Home hint (score ≥ 77% বা confirmed) ────────────
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
        }

        setContentView(root)
    }

    // Hardware back key block
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }
}

