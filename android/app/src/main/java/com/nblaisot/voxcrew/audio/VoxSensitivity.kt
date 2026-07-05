package com.nblaisot.voxcrew.audio

/**
 * User-facing VOX sensitivity, from [MIN] (only very confident, sustained speech
 * transmits — most robust to outdoor noise) to [MAX] (transmits on quieter voices,
 * at the cost of being more easily triggered by background noise). Exposed as a
 * small closed range of integer steps so the UI can drive it with a discrete
 * 5-position [androidx.compose.material3.Slider].
 */
@JvmInline
value class VoxSensitivity(val level: Int) {
    init {
        require(level in MIN..MAX) { "VOX sensitivity must be in $MIN..$MAX, was $level" }
    }

    companion object {
        const val MIN = 1
        const val MAX = 5
        val DEFAULT = VoxSensitivity(3)

        fun coerce(level: Int): VoxSensitivity = VoxSensitivity(level.coerceIn(MIN, MAX))
    }
}
