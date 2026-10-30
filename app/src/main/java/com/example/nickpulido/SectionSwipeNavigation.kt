package com.nickpulido.rcrm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.nickpulido.rcrm.ui.components.SectionNavigationBar
import com.nickpulido.rcrm.ui.theme.RCRMTheme
import kotlin.math.abs

internal object SectionSwipeNavigation {
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
            activity.overridePendingTransition(0, 0)
        }
        val container = SwipeContainer(activity, density, current, navigate)
        container.addView(screen, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RCRMTheme { SectionNavigationBar(current, navigate) } }
        }
        container.setBackgroundResource(R.drawable.bg_space_gradient)
        container.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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

}
