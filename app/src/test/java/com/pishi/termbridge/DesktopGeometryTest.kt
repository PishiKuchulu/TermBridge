package com.pishi.termbridge

import com.pishi.termbridge.domain.DesktopGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Item F-03b: desktop view geometry. */
class DesktopGeometryTest {

    @Test
    fun fitCentresTheFrameAndMapsPoints() {
        // Canvas 1000x1000, frame 800x600: fit = 1.25 (width-bound).
        val t = DesktopGeometry.transform(1000f, 1000f, 800, 600, 1f, 0f, 0f)
        assertEquals(1.25f, t.scale, 0.001f)
        assertEquals(0f, t.offsetX, 0.001f)
        assertEquals(125f, t.offsetY, 0.001f)
        assertEquals(400 to 300, DesktopGeometry.toRemote(t, 800, 600, 500f, 500f))
        // Letterbox area maps to nothing.
        assertNull(DesktopGeometry.toRemote(t, 800, 600, 500f, 50f))
    }

    @Test
    fun zoomScalesAroundTheCentre() {
        val t = DesktopGeometry.transform(1000f, 1000f, 800, 600, 2f, 0f, 0f)
        assertEquals(2.5f, t.scale, 0.001f)
        // The centre of the canvas is still the centre of the frame.
        assertEquals(400 to 300, DesktopGeometry.toRemote(t, 800, 600, 500f, 500f))
    }

    @Test
    fun panIsClampedSoTheFrameCannotVanish() {
        val t = DesktopGeometry.transform(
            1000f, 1000f, 800, 600, 2f, panX = 100_000f, panY = 0f
        )
        // Drawn width is 2000; at most 64 px may leave the left:
        // offsetX <= canvasWidth - 64.
        assertEquals(936f, t.offsetX, 0.001f)
    }

    @Test
    fun degenerateSizesMapToNothing() {
        val t = DesktopGeometry.transform(0f, 100f, 800, 600, 1f, 0f, 0f)
        assertEquals(0f, t.scale, 0.001f)
        assertNull(DesktopGeometry.toRemote(t, 800, 600, 10f, 10f))
    }
}
