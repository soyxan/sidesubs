package com.soyxan.sidesubs

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max

class PlaybackClock {
    private data class State(
        val position: Double,
        val rawPosition: Double,
        val timeMs: Long,
        val playbackState: String,
        val pendingBackwardRaw: Double? = null,
    )

    private val states = mutableMapOf<String, State>()

    @Synchronized
    fun smooth(key: String, rawPosition: Double, playbackState: String?): Double {
        val now = SystemClock.elapsedRealtime()
        val normalized = playbackState?.lowercase() ?: "unknown"
        val previous = states[key]

        if (normalized != "playing") {
            states[key] = State(rawPosition, rawPosition, now, normalized)
            return rawPosition
        }

        var pending: Double? = null
        val position = if (previous == null || previous.playbackState != "playing") {
            rawPosition
        } else {
            val elapsed = max(0L, now - previous.timeMs) / 1000.0
            val predicted = previous.position + elapsed
            val rawChange = rawPosition - previous.rawPosition
            pending = previous.pendingBackwardRaw

            var confirmedBackward = false
            var resolved = predicted

            pending?.let { lower ->
                val lowerProgress = rawPosition - lower
                val stillBelowClock = predicted - rawPosition > SEEK_THRESHOLD
                if (lowerProgress >= 0.5 && stillBelowClock) {
                    resolved = rawPosition
                    confirmedBackward = true
                    pending = null
                } else if (rawPosition >= predicted - SEEK_THRESHOLD) {
                    pending = null
                }
            }

            if (!confirmedBackward) {
                resolved = when {
                    abs(rawChange) <= RAW_CHANGE_EPSILON -> predicted
                    rawChange < -SEEK_THRESHOLD -> {
                        pending = rawPosition
                        predicted
                    }
                    rawChange < 0 -> predicted
                    rawChange > elapsed + SEEK_THRESHOLD -> rawPosition
                    else -> max(predicted, rawPosition)
                }
            }
            resolved
        }

        states[key] = State(position, rawPosition, now, normalized, pending)
        return position
    }

    @Synchronized
    fun clear() = states.clear()

    private companion object {
        const val RAW_CHANGE_EPSILON = 0.20
        const val SEEK_THRESHOLD = 2.5
    }
}
