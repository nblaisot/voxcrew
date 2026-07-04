package com.nblaisot.voxcrew.connectivity.model

import java.util.concurrent.atomic.AtomicLong

@JvmInline
value class GenerationId(val value: Long) {
    fun isObsolete(active: GenerationId?): Boolean = active != null && value < active.value

    companion object {
        private val counter = AtomicLong(0)

        fun next(): GenerationId = GenerationId(counter.incrementAndGet())
    }
}
