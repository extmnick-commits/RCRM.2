package com.nickpulido.rcrm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

internal object SectionSwipeNavigation {
    private val labels = listOf("Home", "Appointments", "Follow-ups", "Pipelines", "Contacts")
    private val colors = intArrayOf(0xFF2EE7FF.toInt(), 0xFF42E99C.toInt(), 0xFFFF70AF.toInt(), 0xFFBD69FF.toInt(), 0xFF70A7FF.toInt())
    private val destinations = listOf(
        MainActivity::class.java, AppointmentsActivity::class.java, FollowUpActivity::class.java,
        PipelinesActivity::class.java, ContactsActivity::class.java
    )

    fun install(activity: Activity, current: Int) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val screen = content.getChildAt(0) ?: return
        content.removeView(screen)
        val density = activity.resources.displayMetrics.density
        val navigate: (Int) -> Unit = navigate@{ target ->
            if (target !in destinations.indices || target == current || activity.isFinishing) return@navigate
            val intent = Intent(activity, destinations[target]).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            activity.startActivity(intent)
            if (current != 0) activity.finish()
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(
                if (target > current) R.anim.section_enter_right else R.anim.section_enter_left,
                if (target > current) R.anim.section_exit_left else R.anim.section_exit_right
            )
        }
        val container = SwipeContainer(activity, density, current, navigate)
        container.addView(screen, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((2 * density).toInt(), 0, (2 * density).toInt(), 0)
            setBackgroundResource(R.drawable.bg_tab_container_pill)
            clipChildren = false
            clipToPadding = false
            labels.forEachIndexed { index, label ->
                addView(NeonTab(activity, label, colors[index], index == current, density).apply {
                    setOnClickListener { navigate(index) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                    when (index) { 1 -> 1.35f; 2 -> 1.15f; else -> 1f }))
            }
        }
        container.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * density).toInt()))
        content.addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            container.keyboardVisible = keyboardVisible
            bar.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(), Insets.NONE)
                .build()
        }
        ViewCompat.requestApplyInsets(container)
    }

    private class SwipeContainer(
        context: Context,
        density: Float,
        private val current: Int,
        private val navigate: (Int) -> Unit
    ) : LinearLayout(context) {
        var keyboardVisible = false
        private val threshold = 64f * density
        private val edgeZone = 56f * density
        private var startX = 0f
        private var startY = 0f
        private var blocked = false
        private var swiping = false

        init { orientation = VERTICAL }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    swiping = false
                    val nearEdge = event.x < edgeZone || event.x > width - edgeZone
                    blocked = keyboardVisible || isProtectedControl(this, event.x, event.y, nearEdge)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    val target = if (dx < 0) current + 1 else current - 1
                    if (!blocked && target in destinations.indices && abs(dx) > threshold && abs(dx) > abs(dy) * 1.5f) {
                        swiping = true
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    val target = if (dx < 0) current + 1 else current - 1
                    if (!blocked && target in destinations.indices && abs(dx) > threshold && abs(dx) > abs(dy) * 1.5f) swiping = true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (swiping && abs(dx) > threshold && abs(dx) > abs(dy) * 1.5f) navigate(if (dx < 0) current + 1 else current - 1)
                    swiping = false
                }
                MotionEvent.ACTION_CANCEL -> swiping = false
            }
            return true
        }

        private fun isProtectedControl(view: View, x: Float, y: Float, nearEdge: Boolean): Boolean {
            if (view is EditText || view is SeekBar) return true
            if (!nearEdge && view !== this && (view.canScrollHorizontally(-1) || view.canScrollHorizontally(1))) return true
            if (view is ViewGroup) {
                for (index in view.childCount - 1 downTo 0) {
                    val child = view.getChildAt(index)
                    if (child.visibility != View.VISIBLE) continue
                    val childX = x + view.scrollX - child.left
                    val childY = y + view.scrollY - child.top
                    if (childX >= 0 && childY >= 0 && childX < child.width && childY < child.height &&
                        isProtectedControl(child, childX, childY, nearEdge)) return true
                }
            }
            return false
        }
    }

    private class NeonTab(context: Context, label: String, private val glowColor: Int, selected: Boolean, density: Float) : AppCompatTextView(context) {
        private val inset = 7f * density
        private val radius = 12f * density
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(if (selected) 100 else 0, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
            setShadowLayer(8f * density, 0f, 0f, Color.argb(if (selected) 220 else 0, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)))
        }
        init {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            val base = requireNotNull(context.getDrawable(R.drawable.bg_frosted_card)).mutate()
            val red = Color.red(glowColor)
            val green = Color.green(glowColor)
            val blue = Color.blue(glowColor)
            val glassTint = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (selected) intArrayOf(
                    Color.argb(140, red, green, blue),
                    Color.argb(100, red, green, blue),
                    Color.argb(65, red, green, blue)
                ) else intArrayOf(0x12FFFFFF, 0x06FFFFFF, 0x00FFFFFF)
            ).apply {
                cornerRadius = radius
                setStroke((if (selected) 1.5f * density else density).toInt().coerceAtLeast(1),
                    if (selected) glowColor else 0x667D8BA2)
            }
            background = InsetDrawable(LayerDrawable(arrayOf(base, glassTint)), inset.toInt())
            text = label
            gravity = Gravity.CENTER
            maxLines = 1
            setSingleLine(true)
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP
            )
            setTextColor(if (selected) Color.WHITE else 0xFFE3EAF6.toInt())
            setPadding((inset + 2 * density).toInt(), 0, (inset + 2 * density).toInt(), 0)
            contentDescription = if (selected) "$label, current page" else "Open $label"
            isSelected = selected
        }

        override fun draw(canvas: Canvas) {
            val rect = RectF(inset, inset, width - inset, height - inset)
            canvas.drawRoundRect(rect, radius, radius, glow)
            super.draw(canvas)
        }
    }
}
