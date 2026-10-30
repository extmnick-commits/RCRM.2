package com.nickpulido.rcrm.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nickpulido.rcrm.ui.theme.GlassFill
import com.nickpulido.rcrm.ui.theme.GlassFrost
import com.nickpulido.rcrm.ui.theme.GlassPanel
import com.nickpulido.rcrm.ui.theme.PrimaryGradientEnd
import com.nickpulido.rcrm.ui.theme.PrimaryGradientStart
import com.nickpulido.rcrm.ui.theme.TextPrimary
import kotlin.math.ceil

private val GlassEdgeBrush: Brush
    get() = androidx.compose.ui.graphics.SolidColor(GlassFrost)

/**
 * Colored outer glow rasterized with [BlurMaskFilter] on a software canvas.
 * Hardware canvases ignore mask filters, so the halo is drawn into an
 * offscreen bitmap and then placed behind the content.
 *
 * [inset] keeps the blur inside the layout slot (use the same value as the
 * padding that reveals the glow). When inset is 0 the bitmap extends past
 * the view so the halo can spill outward.
 */
fun Modifier.neonGlow(
    color: Color,
    cornerRadius: Dp = 16.dp,
    blurRadius: Dp = 14.dp,
    strokeWidth: Dp = 3.dp,
    alpha: Float = 0.9f,
    inset: Dp = 0.dp
): Modifier = this.drawWithCache {
    if (size.width <= 1f || size.height <= 1f) {
        return@drawWithCache onDrawBehind { }
    }

    val blurPx = blurRadius.toPx().coerceAtLeast(1f)
    val strokePx = strokeWidth.toPx().coerceAtLeast(1f)
    val insetPx = inset.toPx().coerceAtLeast(0f)
    val radiusPx = cornerRadius.toPx()
    val overflow = if (insetPx > 0f) 0 else ceil(blurPx * 1.35f).toInt()
    val width = ceil(size.width).toInt().coerceAtLeast(1)
    val height = ceil(size.height).toInt().coerceAtLeast(1)
    val bitmap = android.graphics.Bitmap.createBitmap(
        width + overflow * 2,
        height + overflow * 2,
        android.graphics.Bitmap.Config.ARGB_8888
    )
    val softwareCanvas = android.graphics.Canvas(bitmap)

    fun drawBlurredStroke(blur: Float, widthPx: Float, paintAlpha: Float) {
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            isDither = true
            this.color = color.copy(alpha = paintAlpha.coerceIn(0f, 1f)).toArgb()
            style = android.graphics.Paint.Style.STROKE
            setStrokeWidth(widthPx)
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            maskFilter = BlurMaskFilter(blur.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
        }
        val left = overflow + insetPx
        val top = overflow + insetPx
        val right = overflow + size.width - insetPx
        val bottom = overflow + size.height - insetPx
        if (right > left && bottom > top) {
            softwareCanvas.drawRoundRect(left, top, right, bottom, radiusPx, radiusPx, paint)
        }
    }

    drawBlurredStroke(blur = blurPx * 1.2f, widthPx = strokePx * 2.4f, paintAlpha = alpha * 0.40f)
    drawBlurredStroke(blur = blurPx * 0.7f, widthPx = strokePx, paintAlpha = alpha)

    val image = bitmap.asImageBitmap()
    onDrawBehind {
        drawImage(image, topLeft = Offset(-overflow.toFloat(), -overflow.toFloat()))
    }
}

fun Modifier.frostedGlass(
    shape: Shape,
    fillColor: Color = GlassFill,
    frostColor: Color = Color.Transparent,
    borderBrush: Brush = GlassEdgeBrush,
    borderWidth: Dp = 1.dp
): Modifier = this
    .clip(shape)
    .background(fillColor)
    .background(
        Brush.verticalGradient(
            listOf(
                frostColor,
                Color.Transparent,
                Color.Transparent
            )
        )
    )
    .border(width = borderWidth, brush = borderBrush, shape = shape)

@Composable
fun NeonCard(
    modifier: Modifier = Modifier,
    glowColor: Color,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(GlassPanel)
            .border(1.dp, glowColor.copy(alpha = 0.2f), shape)
            .padding(16.dp)
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    fillColor: Color = GlassPanel,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    var mod = modifier.frostedGlass(shape = shape, fillColor = fillColor)
    mod = when {
        onLongClick != null -> mod.combinedClickable(
            role = Role.Button,
            onLongClick = onLongClick,
            onClick = { onClick?.invoke() }
        )
        onClick != null -> mod.clickable(role = Role.Button) { onClick() }
        else -> mod
    }

    Box(
        modifier = mod.padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun GradientButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val gradient = Brush.horizontalGradient(
        colors = listOf(PrimaryGradientStart, PrimaryGradientEnd)
    )
    Row(
        modifier = modifier
            .clip(shape)
            .background(gradient)
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProvideTextStyle(LocalTextStyle.current.copy(color = TextPrimary)) {
            content()
        }
    }
}

@Composable
fun GlassButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .frostedGlass(shape)
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProvideTextStyle(LocalTextStyle.current.copy(color = TextPrimary)) {
            content()
        }
    }
}
