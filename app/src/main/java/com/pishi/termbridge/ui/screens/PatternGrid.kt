package com.pishi.termbridge.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pishi.termbridge.domain.PatternLock

/**
 * The 3x3 pattern pad (item T-18), shared by the lock screen
 * (unlock), Settings (set with a confirm draw) and the disable
 * confirmation. The finger's path is normalized with
 * [PatternLock.normalize] as it grows — jumped midpoints are
 * added by the same rule the store verifies with, so what the
 * user sees is exactly what is hashed.
 *
 * The grid owns the in-progress stroke: it mirrors [points]
 * (the parent clears it after a wrong attempt by passing an
 * empty list) and reports every change through
 * [onPointsChange]; [onComplete] fires once when the finger
 * lifts, with the final pattern.
 */
@Composable
fun PatternGrid(
    points: List<Int>,
    onPointsChange: (List<Int>) -> Unit,
    onComplete: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: Boolean = false
) {
    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    var stroke by remember { mutableStateOf(points) }
    var touch by remember { mutableStateOf<Offset?>(null) }
    val activeColor = if (error) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(points) {
        if (points != stroke) stroke = points
    }

    fun center(index: Int): Offset {
        val cell = gridSize.width / 3f
        return Offset(
            cell * (index % PatternLock.GRID + 0.5f),
            cell * (index / PatternLock.GRID + 0.5f)
        )
    }

    fun hit(position: Offset): Int? {
        if (gridSize.width == 0) return null
        val radius = gridSize.width / 3f * 0.4f
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        for (i in 0 until PatternLock.DOT_COUNT) {
            val distance = (center(i) - position).getDistance()
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return if (bestDistance <= radius) best else null
    }

    fun extendTo(position: Offset) {
        val dot = hit(position) ?: return
        val next = PatternLock.normalize(stroke + dot)
        if (next != stroke) {
            stroke = next
            onPointsChange(next)
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .onSizeChanged { gridSize = it }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        touch = offset
                        hit(offset)?.let { dot ->
                            val start = PatternLock.normalize(listOf(dot))
                            stroke = start
                            onPointsChange(start)
                        }
                    },
                    onDrag = { change, _ ->
                        touch = change.position
                        extendTo(change.position)
                    },
                    onDragEnd = {
                        touch = null
                        onComplete(stroke)
                    },
                    onDragCancel = {
                        touch = null
                        onComplete(stroke)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (gridSize.width == 0) return@Canvas
            val cell = gridSize.width / 3f
            val centers = stroke.map { center(it) }
            for (i in 0 until centers.size - 1) {
                drawLine(
                    color = activeColor,
                    start = centers[i],
                    end = centers[i + 1],
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            val last = centers.lastOrNull()
            val finger = touch
            if (last != null && finger != null) {
                drawLine(
                    color = activeColor,
                    start = last,
                    end = finger,
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            for (i in 0 until PatternLock.DOT_COUNT) {
                val inStroke = i in stroke
                drawCircle(
                    color = if (inStroke) activeColor else idleColor,
                    radius = cell * if (inStroke) 0.16f else 0.10f,
                    center = center(i)
                )
            }
        }
    }
}
