package com.hft.engine.model

/**
 * Intrusive doubly-linked list node wrapping an [Order].
 *
 * This is an *intrusive* node: the Order itself is embedded, and the
 * prev/next pointers live on the node rather than in a wrapper collection.
 * This eliminates per-node heap allocation that a java.util.LinkedList would
 * require and keeps cache-lines tight.
 *
 * All mutation is confined to the engine's single matching thread,
 * so no synchronisation is needed on prev/next.
 */
class OrderNode(
    @JvmField val order: Order
) {
    @JvmField var prev: OrderNode? = null
    @JvmField var next: OrderNode? = null

    /** Detach this node from whatever list it belongs to. O(1). */
    fun unlink() {
        prev?.next = next
        next?.prev = prev
        prev = null
        next = null
    }
}
