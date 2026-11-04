package com.pishi.termbridge

import com.pishi.termbridge.domain.DeviceActionMapping
import com.pishi.termbridge.domain.ShakeAction
import com.pishi.termbridge.domain.ShakeDetector
import com.pishi.termbridge.domain.SpecialKey
import com.pishi.termbridge.domain.VolumeEffect
import com.pishi.termbridge.domain.VolumeKey
import com.pishi.termbridge.domain.VolumeKeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item T-19: volume-key / shake mapping + shake detection. */
class DeviceActionsTest {

    @Test
    fun disabledIsTheDefaultAndUnknownIdsParseToIt() {
        assertEquals(VolumeKeyAction.DISABLED, VolumeKeyAction.fromId("nonsense"))
        assertEquals(ShakeAction.DISABLED, ShakeAction.fromId(""))
        assertEquals(
            VolumeEffect.None,
            DeviceActionMapping.volumeEffect(VolumeKeyAction.DISABLED, VolumeKey.UP)
        )
    }

    @Test
    fun volumeMappingCoversEveryAction() {
        assertEquals(
            VolumeEffect.AdjustFontSize(+1),
            DeviceActionMapping.volumeEffect(VolumeKeyAction.FONT_SIZE, VolumeKey.UP)
        )
        assertEquals(
            VolumeEffect.AdjustFontSize(-1),
            DeviceActionMapping.volumeEffect(VolumeKeyAction.FONT_SIZE, VolumeKey.DOWN)
        )
        assertEquals(
            VolumeEffect.ArmCtrl,
            DeviceActionMapping.volumeEffect(VolumeKeyAction.CTRL_MODIFIER, VolumeKey.UP)
        )
        assertEquals(
            VolumeEffect.ArmCtrl,
            DeviceActionMapping.volumeEffect(VolumeKeyAction.CTRL_MODIFIER, VolumeKey.DOWN)
        )
        assertEquals(
            VolumeEffect.ScrollPage(+1),
            DeviceActionMapping.volumeEffect(VolumeKeyAction.SCROLL, VolumeKey.UP)
        )
        assertEquals(
            VolumeEffect.ScrollPage(-1),
            DeviceActionMapping.volumeEffect(VolumeKeyAction.SCROLL, VolumeKey.DOWN)
        )
        assertEquals(
            VolumeEffect.SendKey(SpecialKey.ARROW_UP),
            DeviceActionMapping.volumeEffect(VolumeKeyAction.SEND_KEY, VolumeKey.UP)
        )
        assertEquals(
            VolumeEffect.SendKey(SpecialKey.ARROW_DOWN),
            DeviceActionMapping.volumeEffect(VolumeKeyAction.SEND_KEY, VolumeKey.DOWN)
        )
    }

    @Test
    fun shakeMappingCoversEveryAction() {
        assertEquals(
            com.pishi.termbridge.domain.ShakeEffect.None,
            DeviceActionMapping.shakeEffect(ShakeAction.DISABLED)
        )
        assertEquals(
            com.pishi.termbridge.domain.ShakeEffect.ClearScreen,
            DeviceActionMapping.shakeEffect(ShakeAction.CLEAR_SCREEN)
        )
        assertEquals(
            com.pishi.termbridge.domain.ShakeEffect.Disconnect,
            DeviceActionMapping.shakeEffect(ShakeAction.DISCONNECT)
        )
    }

    /** One hard jolt: ~3 g along x for a single sample. */
    private fun jolt(d: ShakeDetector, atMs: Long): Boolean =
        d.onSample(29.5f, 0f, 0f, atMs)

    @Test
    fun shakeNeedsTwoDistinctJoltsInsideTheWindow() {
        val d = ShakeDetector()
        assertFalse(jolt(d, 0L))
        // Same jolt ringing 50 ms later is not a second peak.
        assertFalse(jolt(d, 50L))
        // A second, distinct jolt inside the window fires.
        assertTrue(jolt(d, 400L))
    }

    @Test
    fun shakeDoesNotFireOnQuietOrSlowMovement() {
        val d = ShakeDetector()
        // Resting gravity (~1 g) never fires.
        assertFalse(d.onSample(0f, 0f, 9.81f, 0L))
        assertFalse(d.onSample(0f, 0f, 9.81f, 300L))
        // Jolts too far apart do not combine.
        assertFalse(jolt(d, 1_000L))
        assertFalse(jolt(d, 3_500L))
    }

    @Test
    fun shakeRefractoryBlocksAnImmediateSecondShake() {
        val d = ShakeDetector()
        assertFalse(jolt(d, 0L))
        assertTrue(jolt(d, 300L))
        // Inside the refractory period even two fresh jolts are ignored.
        assertFalse(jolt(d, 500L))
        assertFalse(jolt(d, 800L))
        // After it, a new shake can fire again.
        assertFalse(jolt(d, 1_500L))
        assertTrue(jolt(d, 1_800L))
    }
}
