package com.pishi.termbridge.domain

import kotlin.math.sqrt

/** Which hardware volume key was pressed (item T-19). */
enum class VolumeKey { UP, DOWN }

/**
 * What the volume keys do while a terminal session is showing
 * (item T-19). Stored in settings by [id]; anything unknown
 * parses back to DISABLED, which is also the default — with
 * DISABLED the keys keep their system behaviour (media volume)
 * everywhere in the app.
 */
enum class VolumeKeyAction(val id: String) {
    DISABLED("disabled"),
    FONT_SIZE("font_size"),
    CTRL_MODIFIER("ctrl"),
    SCROLL("scroll"),
    SEND_KEY("send_key");

    companion object {
        val IDS: List<String> = entries.map { it.id }
        fun fromId(id: String): VolumeKeyAction =
            entries.firstOrNull { it.id == id } ?: DISABLED
    }
}

/** What a device shake does (item T-19). Default DISABLED. */
enum class ShakeAction(val id: String) {
    DISABLED("disabled"),
    CLEAR_SCREEN("clear"),
    DISCONNECT("disconnect");

    companion object {
        val IDS: List<String> = entries.map { it.id }
        fun fromId(id: String): ShakeAction =
            entries.firstOrNull { it.id == id } ?: DISABLED
    }
}

/** The resolved effect of one volume-key press. Pure data: the
 *  session layer executes it, so the whole mapping is testable
 *  without Android. */
sealed interface VolumeEffect {
    data object None : VolumeEffect
    /** Change the terminal font size by [deltaSp] steps. */
    data class AdjustFontSize(val deltaSp: Int) : VolumeEffect
    /** Arm the Ctrl latch once (same cycle as the on-screen key). */
    data object ArmCtrl : VolumeEffect
    /** Scroll the scrollback by one page: +1 = older, -1 = newer. */
    data class ScrollPage(val direction: Int) : VolumeEffect
    /** Send a special key to the remote shell. */
    data class SendKey(val key: SpecialKey) : VolumeEffect
}

/** The resolved effect of a detected shake. */
sealed interface ShakeEffect {
    data object None : ShakeEffect
    data object ClearScreen : ShakeEffect
    data object Disconnect : ShakeEffect
}

/**
 * The pure mapping behind item T-19. Volume SEND_KEY sends the
 * arrow keys (up = shell history up, down = history down);
 * SCROLL moves one page. Both volume keys arm Ctrl: the latch
 * cycles OFF -> ONCE -> LOCKED -> OFF per press, so either key
 * advances the same cycle, exactly like tapping the on-screen
 * Ctrl button.
 */
object DeviceActionMapping {
    fun volumeEffect(action: VolumeKeyAction, key: VolumeKey): VolumeEffect =
        when (action) {
            VolumeKeyAction.DISABLED -> VolumeEffect.None
            VolumeKeyAction.FONT_SIZE -> VolumeEffect.AdjustFontSize(
                if (key == VolumeKey.UP) +1 else -1
            )
            VolumeKeyAction.CTRL_MODIFIER -> VolumeEffect.ArmCtrl
            VolumeKeyAction.SCROLL -> VolumeEffect.ScrollPage(
                if (key == VolumeKey.UP) +1 else -1
            )
            VolumeKeyAction.SEND_KEY -> VolumeEffect.SendKey(
                if (key == VolumeKey.UP) SpecialKey.ARROW_UP else SpecialKey.ARROW_DOWN
            )
        }

    fun shakeEffect(action: ShakeAction): ShakeEffect = when (action) {
        ShakeAction.DISABLED -> ShakeEffect.None
        ShakeAction.CLEAR_SCREEN -> ShakeEffect.ClearScreen
        ShakeAction.DISCONNECT -> ShakeEffect.Disconnect
    }
}

/**
 * Accelerometer shake detection (item T-19), pure so the
 * thresholds are unit-tested: a shake fires when at least
 * [requiredPeaks] samples exceed [thresholdG] g-force inside a
 * [windowMs] window. Samples closer than [sameJoltMs] count as
 * one peak (a single jolt rings for several samples), and after
 * firing, [refractoryMs] must pass before another shake can
 * fire — a disconnect shake must not also clear the screen of
 * the next session.
 */
class ShakeDetector(
    private val thresholdG: Float = 2.7f,
    private val requiredPeaks: Int = 2,
    private val windowMs: Long = 1_000L,
    private val sameJoltMs: Long = 150L,
    private val refractoryMs: Long = 1_000L
) {
    private val peakTimes = mutableListOf<Long>()
    private var lastPeakAt = Long.MIN_VALUE / 2
    private var lastFiredAt = Long.MIN_VALUE / 2

    /** Feed one accelerometer sample (m/s^2 per axis, device
     *  time in ms). Returns true exactly once per shake. */
    fun onSample(x: Float, y: Float, z: Float, nowMs: Long): Boolean {
        val g = sqrt(x * x + y * y + z * z) / G_FORCE
        if (g < thresholdG) return false
        if (nowMs - lastPeakAt < sameJoltMs) return false
        lastPeakAt = nowMs
        if (nowMs - lastFiredAt < refractoryMs) return false
        peakTimes.removeAll { nowMs - it > windowMs }
        peakTimes += nowMs
        if (peakTimes.size < requiredPeaks) return false
        peakTimes.clear()
        lastFiredAt = nowMs
        return true
    }

    private companion object {
        const val G_FORCE = 9.80665f
    }
}
