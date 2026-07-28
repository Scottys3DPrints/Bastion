package com.bastion.app.guard.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The wall a man meets when he walks into a guarded screen.
 *
 * Drawn as a TYPE_ACCESSIBILITY_OVERLAY, which the service is allowed to place
 * without the far more invasive "draw over other apps" permission.
 *
 * Tone matters as much as the block: this screen is steady and respectful, never
 * a red alarm and never a scold. It states the boundary the user set for himself
 * and offers a way forward.
 */
class ShieldOverlay(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var dimView: View? = null

    val isShowing: Boolean get() = view != null

    fun show(
        title: String,
        message: String,
        primaryLabel: String,
        onPrimary: () -> Unit,
        secondaryLabel: String? = null,
        onSecondary: (() -> Unit)? = null,
        autoDismissMillis: Long? = null,
    ) {
        handler.post {
            if (view != null) return@post
            val content = buildView(title, message, primaryLabel, onPrimary, secondaryLabel, onSecondary)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.CENTER }

            runCatching {
                windowManager.addView(content, params)
                view = content
            }
            autoDismissMillis?.let { handler.postDelayed({ hide() }, it) }
        }
    }

    fun hide() {
        handler.post {
            view?.let { runCatching { windowManager.removeView(it) } }
            view = null
        }
    }

    /**
     * The grayscale fallback when WRITE_SECURE_SETTINGS has not been granted.
     * Not true desaturation — a translucent veil that takes the shine off a feed
     * without hiding it. Honest about being a lesser tool.
     */
    fun showDimVeil(alpha: Float = 0.28f) {
        handler.post {
            if (dimView != null) return@post
            val veil = View(service).apply { setBackgroundColor(Color.argb((alpha * 255).toInt(), 12, 14, 24)) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT,
            )
            runCatching {
                windowManager.addView(veil, params)
                dimView = veil
            }
        }
    }

    fun hideDimVeil() {
        handler.post {
            dimView?.let { runCatching { windowManager.removeView(it) } }
            dimView = null
        }
    }

    fun destroy() {
        hide()
        hideDimVeil()
        handler.removeCallbacksAndMessages(null)
    }

    private fun buildView(
        title: String,
        message: String,
        primaryLabel: String,
        onPrimary: () -> Unit,
        secondaryLabel: String?,
        onSecondary: (() -> Unit)?,
    ): View {
        val ctx = service
        val root = FrameLayout(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF5090C16.toInt(), 0xF5141B33.toInt(), 0xF52C2C4E.toInt()),
            )
            isClickable = true
        }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(48), dp(36), dp(48))
        }

        column.addView(TextView(ctx).apply {
            text = "◇"
            setTextColor(0xFFC8A24B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            gravity = Gravity.CENTER
        })

        column.addView(TextView(ctx).apply {
            text = title
            setTextColor(0xFFEAEEF7.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 27f)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })

        column.addView(TextView(ctx).apply {
            text = message
            setTextColor(0xFF96A0BA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setLineSpacing(dp(6).toFloat(), 1f)
            setPadding(0, dp(14), 0, dp(34))
        })

        column.addView(Button(ctx).apply {
            text = primaryLabel
            setTextColor(0xFF090C16.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFC8A24B.toInt())
            }
            setPadding(dp(28), dp(14), dp(28), dp(14))
            setOnClickListener { onPrimary() }
        })

        if (secondaryLabel != null && onSecondary != null) {
            val secondary = Button(ctx).apply {
                text = secondaryLabel
                // Some themes uppercase button text, which fights the app's
                // deliberately steady tone at exactly the wrong moment.
                transformationMethod = null
                setTextColor(0xFF96A0BA.toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(0x00000000)
                    setStroke(dp(1), 0xFF2E3A5E.toInt())
                }
                setPadding(dp(28), dp(14), dp(28), dp(14))
                setOnClickListener { onSecondary() }
            }
            // Params must be supplied to addView: reading layoutParams before a
            // view is attached returns null, so the gap was silently dropped and
            // the two buttons rendered flush together.
            column.addView(
                secondary,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }

        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        )
        return root
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()
}
