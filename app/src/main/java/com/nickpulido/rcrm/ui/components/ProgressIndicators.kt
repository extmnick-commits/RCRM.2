package com.nickpulido.rcrm.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nickpulido.rcrm.ui.theme.NeonCyan
import kotlin.math.ceil

@Composable
fun GlowingProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = NeonCyan,
    indicatorSize: Dp = 116.dp,
    strokeWidth: Dp = 8.dp,
    glowRadius: Dp = 12.dp,
    content: @Composable () -> Unit
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier.size(indicatorSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokePx = strokeWidth.toPx()
            val inset = glowRadius.toPx() + strokePx
            drawArc(
                color = color.copy(alpha = 0.20f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                style = Stroke(width = strokePx, cap = StrokeCap.Butt)
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .glowingArc(
                    progress = clamped,
                    color = color,
                    strokeWidth = strokeWidth,
                    glowRadius = glowRadius
                )
        )
        if (clamped > 0f) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val strokePx = strokeWidth.toPx()
                val inset = glowRadius.toPx() + strokePx
                val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                val topLeft = Offset(inset, inset)
                val sweep = 360f * clamped
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
                drawArc(
                    color = Color.White.copy(alpha = 0.82f),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx * 0.36f, cap = StrokeCap.Round)
                )
            }
        }
        content()
    }
}

private fun Modifier.glowingArc(
    progress: Float,
    color: Color,
    strokeWidth: Dp,
    glowRadius: Dp
): Modifier = this.drawWithCache {
    if (size.minDimension <= 1f || progress <= 0f) {
        return@drawWithCache onDrawBehind { }
    }

    val strokePx = strokeWidth.toPx()
    val blurPx = glowRadius.toPx().coerceAtLeast(1f)
    val inset = blurPx + strokePx
    val width = ceil(size.width).toInt().coerceAtLeast(1)
    val height = ceil(size.height).toInt().coerceAtLeast(1)
    val bitmap = android.graphics.Bitmap.createBitmap(
        width,
        height,
        android.graphics.Bitmap.Config.ARGB_8888
    )
    val softwareCanvas = android.graphics.Canvas(bitmap)
    val oval = android.graphics.RectF(inset, inset, size.width - inset, size.height - inset)
    val sweep = 360f * progress

    fun drawBlurredArc(blur: Float, widthPx: Float, paintAlpha: Float) {
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            isDither = true
            this.color = color.copy(alpha = paintAlpha.coerceIn(0f, 1f)).toArgb()
            style = android.graphics.Paint.Style.STROKE
            setStrokeWidth(widthPx)
            strokeCap = android.graphics.Paint.Cap.ROUND
            maskFilter = BlurMaskFilter(blur.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
        }
        softwareCanvas.drawArc(oval, -90f, sweep, false, paint)
    }

    drawBlurredArc(blur = blurPx * 1.15f, widthPx = strokePx * 2.1f, paintAlpha = 0.55f)
    drawBlurredArc(blur = blurPx * 0.65f, widthPx = strokePx * 1.15f, paintAlpha = 0.95f)

    val image = bitmap.asImageBitmap()
    onDrawBehind {
        drawImage(image)
    }
}
