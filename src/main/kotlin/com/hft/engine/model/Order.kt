package com.hft.engine.model

import java.util.concurrent.atomic.AtomicLong

/**
 * Immutable order representation.
 *
 * Prices are stored as Long ticks (e.g., $100.50 → 10050) to avoid
 * floating-point arithmetic errors in the hot path.
 *
 * @property orderId   Unique monotonically-increasing ID
 * @property side      BUY or SELL
 * @property priceTicks  Limit price in ticks (1 tick = 0.01 currency unit)
 * @property quantity  Remaining quantity (mutable only inside the engine)
 * @property timestamp Epoch-nanos for price-time priority tie-breaking
 */
data class Order(
    val orderId: Long,
    val side: Side,
    val priceTicks: Long,
    var quantity: Long,
    val timestamp: Long = System.nanoTime()
) {
    companion object {
        private val ID_GEN = AtomicLong(0)

        /** Generates a globally unique, monotonically-increasing order ID. */
        @JvmStatic
        fun nextId(): Long = ID_GEN.incrementAndGet()
    }
}
