package com.ftt.bulldogblocker.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.ftt.bulldogblocker.ml.FalsePositiveDB
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Full-screen block overlay।
 *
 * score < 85% হলে True / False report bottom sheet আসে (half-screen popup):
 *   ✅ সঠিক   → confirmed true DB-তে hash add হয়
 *   ❌ ভুল    → false positive DB-তে hash add হয়
 *   এড়িয়ে যান → কোনো DB update নেই, sheet বন্ধ হয়
 *
 * score ≥ 85% বা confirmed true → শুধু block screen, কোনো popup নেই।
 */
class BlockScreenActivity : AppCompatActivity() {

    // BUG FIX #3: dialog field হিসেবে রাখো — onDestroy-এ dismiss করা যাবে।
    // আগে local var ছিল → Activity destroy হলে dialog টিকে থাকতো → WindowLeakedException
    private var reportDialog: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val reason     = intent.getStringExtra("reason")          ?: "🚫 কন্টেন্ট ব্লক করা হয়েছে"
        val imageHash  = intent.getLongExtra("image_hash",   0L)
        val score      = intent.getFloatExtra("score",       1f)
        val showReport = intent.getBooleanExtra("show_report", false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* blocked */ }
        })

        // ── Full-screen red block overlay ────────────────────────────
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            setPadding(dp(48), 0, dp(48), 0)
            setBackgroundColor(Color.parseColor("#B71C1C"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(tv("🐶", 72f, Color.WHITE, Gravity.CENTER, mb = dp(8)))
        root.addView(tv("ব্লক করা হয়েছে", 28f, Color.WHITE, Gravity.CENTER, mt = dp(4), mb = dp(8)))
        root.addView(tv(reason, 15f, Color.parseColor("#FFCDD2"), Gravity.CENTER, mb = dp(24)))

        if (showReport && imageHash != 0L) {
            root.addView(tv(
                "নিচের রিপোর্ট পপআপটি পূরণ করুন ↓",
                13f, Color.parseColor("#FFECB3"), Gravity.CENTER
            ))
        } else {
            root.addView(tv("Home বাটন চাপুন", 14f, Color.parseColor("#EF9A9A"), Gravity.CENTER))
        }

        setContentView(root)

        if (showReport && imageHash != 0L) {
            showReportSheet(imageHash, score)
        }
    }

    override fun onDestroy() {
        // BUG FIX #3: Dialog leak প্রতিরোধ — Activity destroy হলে dialog বন্ধ করো
        reportDialog?.dismiss()
        reportDialog = null
        super.onDestroy()
    }

    // ── Report Bottom Sheet ───────────────────────────────────────────

    private fun showReportSheet(imageHash: Long, score: Float) {
        val scorePct = (score * 100).toInt()

        val dialog = BottomSheetDialog(this)
        reportDialog = dialog  // BUG FIX #3: field-এ রাখো
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(4), dp(24), dp(40))
            background  = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C3A"))
                // topLeft, topRight rounded; bottomLeft, bottomRight sharp
                cornerRadii = floatArrayOf(
                    dp(24f), dp(24f),   // top-left x,y
                    dp(24f), dp(24f),   // top-right x,y
                    0f, 0f,             // bottom-right x,y
                    0f, 0f              // bottom-left x,y
                )
            }
        }

        // ── Drag handle ────────────────────────────────────────────────
        container.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
            addView(View(this@BlockScreenActivity).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FFFFFF50"))
                    cornerRadius = dp(3f)
                }
                layoutParams = FrameLayout.LayoutParams(dp(40), dp(5)).apply {
                    gravity = Gravity.CENTER
                }
            })
        })

        // ── Header row: icon + title ──────────────────────────────────
        // BUG FIX #2: Horizontal LinearLayout-এ tv() এর MATCH_PARENT width ভুল।
        // 🔍 emoji MATCH_PARENT হলে পুরো row ভরে, title দেখা যায় না।
        // Fix: emoji-র জন্য WRAP_CONTENT width ব্যবহার করো।
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, dp(20)) }

            // WRAP_CONTENT width for emoji — horizontal layout-এ MATCH_PARENT চলে না
            addView(TextView(this@BlockScreenActivity).apply {
                text     = "🔍"
                textSize = 22f
                setTextColor(Color.WHITE)
                gravity  = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(10), 0) }
            })

            addView(LinearLayout(this@BlockScreenActivity).apply {
                orientation  = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(tv("রিপোর্ট করুন", 17f, Color.WHITE, Gravity.START))
                addView(tv(
                    "এই ছবিটি সঠিকভাবে ব্লক হয়েছে?",
                    12f, Color.parseColor("#9090CC"), Gravity.START, mt = dp(2)
                ))
            })
        })

        // ── Confidence score card ─────────────────────────────────────
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(20)) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A2A4A"))
                cornerRadius = dp(14f)
            }

            // Label row
            addView(LinearLayout(this@BlockScreenActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(8)) }

                addView(tv("Model Confidence", 12f, Color.parseColor("#9090CC"), Gravity.CENTER_VERTICAL)
                    .apply {
                        // weight=1 দিয়ে বাকি space নাও, score % আপনার জায়গা পাক
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    }
                )

                // BUG FIX #2: Score % text WRAP_CONTENT হওয়া উচিত, MATCH_PARENT নয়।
                // MATCH_PARENT হলে weight-1f সিবলিংয়ের সাথে overlap করে দেখা যায় না।
                addView(TextView(this@BlockScreenActivity).apply {
                    text     = "${scorePct}%"
                    textSize = 18f
                    setTextColor(scoreColor(scorePct))
                    setTypeface(null, Typeface.BOLD)
                    gravity  = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
            })

            // Progress bar
            // BUG FIX #1: buildProgressDrawable() ঠিক করা হয়েছে।
            // আগে: layers variable create করে IDs set করতো, কিন্তু return করতো না!
            //       .let{} block-এ নতুন LayerDrawable তৈরি হতো — IDs ছাড়া।
            //       ProgressBar android.R.id.background / progress ID না পেলে blank bar দেখায়।
            addView(ProgressBar(
                this@BlockScreenActivity, null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                max      = 100
                progress = scorePct
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
                )
                progressDrawable = buildProgressDrawable(scorePct)
            })

            addView(tv(
                "85%-এর নিচে বলে আপনার মতামত চাওয়া হচ্ছে",
                11f, Color.parseColor("#7070AA"), Gravity.END, mt = dp(6)
            ))
        })

        // ── ✅ সঠিক button ────────────────────────────────────────────
        container.addView(Button(this).apply {
            text     = "✅  হ্যাঁ, সঠিকভাবে ব্লক হয়েছে"
            textSize = 15f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { setMargins(0, 0, 0, dp(12)) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1B5E20"))
                cornerRadius = dp(14f)
            }
            setOnClickListener {
                FalsePositiveDB.addTrue(this@BlockScreenActivity, imageHash)
                dialog.dismiss()
            }
        })

        // ── ❌ ভুল button ─────────────────────────────────────────────
        container.addView(Button(this).apply {
            text     = "❌  না, এটি ভুল ব্লক (False Positive)"
            textSize = 15f
            setTextColor(Color.parseColor("#EF9A9A"))
            isAllCaps = false
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { setMargins(0, 0, 0, dp(12)) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A2A4A"))
                cornerRadius = dp(14f)
                setStroke(dp(1), Color.parseColor("#EF5350"))
            }
            setOnClickListener {
                FalsePositiveDB.addFalse(this@BlockScreenActivity, imageHash)
                dialog.dismiss()
            }
        })

        // ── এড়িয়ে যান (skip) ─────────────────────────────────────────
        container.addView(tv(
            "এড়িয়ে যান — পরে সিদ্ধান্ত নেব",
            13f, Color.parseColor("#6060AA"), Gravity.CENTER, mt = dp(4)
        ).apply {
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(container)

        dialog.setOnShowListener {
            // BottomSheet inner container background transparent করো —
            // না করলে rounded corners-এর পিছনে সাদা/ডিফল্ট color দেখা যাবে।
            val sheetParent = container.parent as? FrameLayout
            sheetParent?.let { parent ->
                parent.setBackgroundColor(Color.TRANSPARENT) // inner container transparent
                val behavior = BottomSheetBehavior.from(parent)
                behavior.peekHeight = resources.displayMetrics.heightPixels / 2
                behavior.state      = BottomSheetBehavior.STATE_COLLAPSED
                behavior.isDraggable = true
                behavior.skipCollapsed = false
            }
            // Window background transparent — rounded corners দেখাবে
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }

        dialog.show()
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun scoreColor(pct: Int): Int = when {
        pct >= 70 -> Color.parseColor("#FF5252")
        pct >= 50 -> Color.parseColor("#FFAB40")
        else      -> Color.parseColor("#69F0AE")
    }

    /**
     * BUG FIX #1: buildProgressDrawable() সংশোধন।
     *
     * আগের কোডের সমস্যা:
     *   val layers = LayerDrawable(...) → setId(0, background), setId(1, progress)
     *   return ClipDrawable(...).let { LayerDrawable(arrayOf(track, it)) }
     *   ↑ এই নতুন LayerDrawable-এ কোনো ID নেই!
     *   ProgressBar setId ছাড়া LayerDrawable পেলে progress টি draw করে না → blank bar।
     *
     * Fix: একটাই LayerDrawable তৈরি করো, সেটিতেই ID set করো, সেটিই return করো।
     */
    private fun buildProgressDrawable(pct: Int): LayerDrawable {
        val trackColor = Color.parseColor("#3A3A5C")
        val fillColor  = scoreColor(pct)

        val track = GradientDrawable().apply {
            setColor(trackColor)
            cornerRadius = dp(4f)
        }
        val fill = ClipDrawable(
            GradientDrawable().apply {
                setColor(fillColor)
                cornerRadius = dp(4f)
            },
            Gravity.START,
            ClipDrawable.HORIZONTAL
        )
        val layers = LayerDrawable(arrayOf(track, fill))
        layers.setId(0, android.R.id.background) // ← ProgressBar এই ID দিয়ে track খোঁজে
        layers.setId(1, android.R.id.progress)   // ← ProgressBar এই ID দিয়ে fill খোঁজে
        return layers                             // ← আগে এই variable return হতো না!
    }

    // tv() helper — MATCH_PARENT width (vertical layout-এ ব্যবহার করো)।
    // Horizontal layout-এ সরাসরি TextView তৈরি করো WRAP_CONTENT দিয়ে।
    private fun tv(
        text:    String,
        size:    Float,
        color:   Int,
        gravity: Int = Gravity.START,
        ml: Int = 0, mt: Int = 0, mr: Int = 0, mb: Int = 0
    ) = TextView(this).apply {
        this.text = text
        textSize  = size
        setTextColor(color)
        this.gravity = gravity
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(ml, mt, mr, mb) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
