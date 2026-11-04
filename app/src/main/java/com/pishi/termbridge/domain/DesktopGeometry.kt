package com.pishi.termbridge.domain

/**
 * Item F-03b: view geometry for the remote desktop — how the
 * framebuffer maps onto the canvas under fit + user zoom/pan,
 * and how a touch point maps back to remote pixels. Pure math,
 * shared by the draw code and the gesture handlers so the two
 * can never disagree.
 *
 * The transform: fit the frame into the canvas (letterboxed),
 * multiply by [zoom] (1 = fit), centre, then translate by the
 * pan. Pan is clamped so at least a slice of the frame always
 * stays on screen — a desktop dragged entirely out of view is
 * unrecoverable without a reset button, so it is prevented
 * here instead.
 */
object DesktopGeometry {
    const val MAX_ZOOM = 4f

    data class Transform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float
    )

    fun transform(
        canvasWidth: Float,
        canvasHeight: Float,
        frameWidth: Int,
        frameHeight: Int,
        zoom: Float,
        panX: Float,
        panY: Float
    ): Transform {
        if (canvasWidth <= 0f || canvasHeight <= 0f ||
            frameWidth <= 0 || frameHeight <= 0
        ) {
            return Transform(0f, 0f, 0f)
        }
        val fit = minOf(
            canvasWidth / frameWidth,
            canvasHeight / frameHeight
        )
        val z = zoom.coerceIn(1f, MAX_ZOOM)
        val scale = fit * z
        val drawnW = frameWidth * scale
        val drawnH = frameHeight * scale
        val baseX = (canvasWidth - drawnW) / 2f
        val baseY = (canvasHeight - drawnH) / 2f
        // At least 64 px of the frame (or the whole frame when
        // smaller) must remain inside the canvas on each axis.
        val keepX = minOf(64f, drawnW)
        val keepY = minOf(64f, drawnH)
        val ox = (baseX + panX).coerceIn(
            -(drawnW - keepX), canvasWidth - keepX
        )
        val oy = (baseY + panY).coerceIn(
            -(drawnH - keepY), canvasHeight - keepY
        )
        return Transform(scale, ox, oy)
    }

    /**
     * Canvas point → remote pixel, or null when the point is
     * outside the drawn frame (a letterbox tap does nothing).
     */
    fun toRemote(
        transform: Transform,
        frameWidth: Int,
        frameHeight: Int,
        px: Float,
        py: Float
    ): Pair<Int, Int>? {
        if (transform.scale <= 0f) return null
        val x = ((px - transform.offsetX) / transform.scale).toInt()
        val y = ((py - transform.offsetY) / transform.scale).toInt()
        if (x !in 0 until frameWidth || y !in 0 until frameHeight) {
            return null
        }
        return x to y
    }
}
