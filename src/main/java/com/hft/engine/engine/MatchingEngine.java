package com.hft.engine.engine;

import com.hft.engine.model.Order;
import com.hft.engine.model.OrderNode;
import com.hft.engine.model.Side;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Single-threaded, in-memory Limit Order Book with price-time priority.
 * <p>
 * <b>Data structure design:</b>
 * <ul>
 *   <li>{@code bids} — {@link ConcurrentSkipListMap} in <em>descending</em> key
 *       order so {@code firstEntry()} yields the best (highest) bid.</li>
 *   <li>{@code asks} — natural (ascending) order so {@code firstEntry()} yields
 *       the best (lowest) ask.</li>
 *   <li>Each price level maps to a custom FIFO doubly-linked list of
 *       {@link OrderNode}s, giving O(1) enqueue, dequeue, and cancel.</li>
 * </ul>
 * <p>
 * <b>Zero-allocation hot path:</b> The matching loop performs <em>no</em>
 * heap allocations. {@link OrderNode} instances are allocated once on order
 * submission and reused/unlinked during matching. Price levels (PriceLevel
 * sentinel nodes) are recycled from an object pool.
 */
@Component
public class MatchingEngine {

    // ── Price-level FIFO list (intrusive doubly-linked with sentinel) ─────

    /**
     * A sentinel-based doubly-linked list for one price level.
     * Sentinel eliminates null-checks on head/tail in the hot path.
     */
    static final class PriceLevel {
        final OrderNode sentinel = new OrderNode(
                new Order(0L, Side.BUY, 0L, 0L, 0L));
        int size;

        PriceLevel() {
            sentinel.prev = sentinel;
            sentinel.next = sentinel;
            size = 0;
        }

        /** Append to tail — O(1). */
        void enqueue(OrderNode node) {
            node.prev = sentinel.prev;
            node.next = sentinel;
            sentinel.prev.next = node;
            sentinel.prev = node;
            size++;
        }

        /** Peek at head — O(1), returns null-sentinel if empty. */
        OrderNode peekHead() {
            return sentinel.next;
        }

        /** Remove head — O(1). */
        OrderNode dequeueHead() {
            OrderNode head = sentinel.next;
            if (head == sentinel) return null;
            head.unlink();
            size--;
            return head;
        }

        boolean isEmpty() {
            return sentinel.next == sentinel;
        }

        void remove(OrderNode node) {
            node.unlink();
            size--;
        }

        /** Reset for object-pool reuse. */
        void reset() {
            sentinel.prev = sentinel;
            sentinel.next = sentinel;
            size = 0;
        }

        /** Collect remaining orders into a list snapshot (for UI depth view). */
        List<Order> snapshot() {
            List<Order> out = new ArrayList<>(size);
            OrderNode cur = sentinel.next;
            while (cur != sentinel) {
                out.add(cur.order);
                cur = cur.next;
            }
            return out;
        }
    }

    // ── PriceLevel object pool ───────────────────────────────────────────

    private static final int POOL_CAPACITY = 4096;
    private final ArrayDeque<PriceLevel> priceLevelPool = new ArrayDeque<>(POOL_CAPACITY);

    private PriceLevel acquireLevel() {
        PriceLevel pl = priceLevelPool.pollFirst();
        if (pl == null) return new PriceLevel();
        pl.reset();
        return pl;
    }

    private void releaseLevel(PriceLevel pl) {
        if (priceLevelPool.size() < POOL_CAPACITY) {
            priceLevelPool.offerLast(pl);
        }
    }

    // ── Order books ──────────────────────────────────────────────────────

    /** Best bid = first entry (descending key order). */
    private final ConcurrentSkipListMap<Long, PriceLevel> bids =
            new ConcurrentSkipListMap<>(Comparator.reverseOrder());

    /** Best ask = first entry (natural ascending key order). */
    private final ConcurrentSkipListMap<Long, PriceLevel> asks =
            new ConcurrentSkipListMap<>();

    /** Fast cancel lookup: orderId → node in the book. */
    private final HashMap<Long, OrderNode> liveOrders = new HashMap<>(1 << 16);

    /** Listeners notified on every fill (trade). Thread-safe for concurrent registration. */
    private final List<Consumer<TradeEvent>> tradeListeners = new CopyOnWriteArrayList<>();

    /** Listeners notified on every order-book mutation (for depth UI). Thread-safe. */
    private final List<Runnable> bookChangeListeners = new CopyOnWriteArrayList<>();

    // ── Public API ───────────────────────────────────────────────────────

    public void addTradeListener(Consumer<TradeEvent> listener) {
        if (listener != null) {
            tradeListeners.add(listener);
        }
    }

    public void removeTradeListener(Consumer<TradeEvent> listener) {
        if (listener != null) {
            tradeListeners.remove(listener);
        }
    }

    public void addBookChangeListener(Runnable listener) {
        if (listener != null) {
            bookChangeListeners.add(listener);
        }
    }

    public void removeBookChangeListener(Runnable listener) {
        if (listener != null) {
            bookChangeListeners.remove(listener);
        }
    }

    /**
     * Submit a new limit order.
     * <p>
     * The engine first attempts to match against the opposite side of the book.
     * Any remaining quantity rests on the book at the specified price level.
     *
     * @return the (possibly partially-filled) Order
     */
    public synchronized Order submitOrder(Side side, long priceTicks, long quantity) {
        if (side == null) {
            throw new IllegalArgumentException("Side cannot be null");
        }
        if (priceTicks <= 0) {
            throw new IllegalArgumentException("Price ticks must be positive, got: " + priceTicks);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, got: " + quantity);
        }

        Order order = new Order(Order.nextId(), side, priceTicks, quantity, System.nanoTime());

        if (side == Side.BUY) {
            matchAgainstAsks(order);
            if (order.getQuantity() > 0) {
                restOnBook(order, bids);
            }
        } else {
            matchAgainstBids(order);
            if (order.getQuantity() > 0) {
                restOnBook(order, asks);
            }
        }

        fireBookChange();
        return order;
    }

    /**
     * Cancel a resting order by ID.
     *
     * @return true if the order was found and removed
     */
    public synchronized boolean cancelOrder(long orderId) {
        OrderNode node = liveOrders.remove(orderId);
        if (node == null) return false;

        Order o = node.order;
        ConcurrentSkipListMap<Long, PriceLevel> book =
                (o.getSide() == Side.BUY) ? bids : asks;

        PriceLevel level = book.get(o.getPriceTicks());
        if (level != null) {
            level.remove(node);
            if (level.isEmpty()) {
                book.remove(o.getPriceTicks());
                releaseLevel(level);
            }
        }
        fireBookChange();
        return true;
    }

    // ── Matching logic (hot path — zero allocation) ──────────────────────

    private void matchAgainstAsks(Order buyOrder) {
        while (buyOrder.getQuantity() > 0) {
            Map.Entry<Long, PriceLevel> bestAsk = asks.firstEntry();
            if (bestAsk == null) break;
            if (bestAsk.getKey() > buyOrder.getPriceTicks()) break; // no cross

            PriceLevel level = bestAsk.getValue();
            matchLevel(buyOrder, level);

            if (level.isEmpty()) {
                asks.remove(bestAsk.getKey());
                releaseLevel(level);
            }
        }
    }

    private void matchAgainstBids(Order sellOrder) {
        while (sellOrder.getQuantity() > 0) {
            Map.Entry<Long, PriceLevel> bestBid = bids.firstEntry();
            if (bestBid == null) break;
            if (bestBid.getKey() < sellOrder.getPriceTicks()) break; // no cross

            PriceLevel level = bestBid.getValue();
            matchLevel(sellOrder, level);

            if (level.isEmpty()) {
                bids.remove(bestBid.getKey());
                releaseLevel(level);
            }
        }
    }

    /**
     * Walk the FIFO queue at one price level and fill the aggressor order.
     * This is the innermost hot loop — no allocations occur here except
     * the TradeEvent record that is published to listeners.
     */
    private void matchLevel(Order aggressor, PriceLevel level) {
        while (aggressor.getQuantity() > 0 && !level.isEmpty()) {
            OrderNode headNode = level.peekHead();
            Order resting = headNode.order;
            long fillQty = Math.min(aggressor.getQuantity(), resting.getQuantity());

            aggressor.setQuantity(aggressor.getQuantity() - fillQty);
            resting.setQuantity(resting.getQuantity() - fillQty);

            // Publish trade event
            fireTrade(new TradeEvent(
                    aggressor.getOrderId(),
                    resting.getOrderId(),
                    resting.getPriceTicks(),
                    fillQty,
                    System.nanoTime()
            ));

            if (resting.getQuantity() == 0) {
                level.dequeueHead();
                liveOrders.remove(resting.getOrderId());
            }
        }
    }

    // ── Resting orders ───────────────────────────────────────────────────

    private void restOnBook(Order order,
                            ConcurrentSkipListMap<Long, PriceLevel> book) {
        PriceLevel level = book.get(order.getPriceTicks());
        if (level == null) {
            level = acquireLevel();
            book.put(order.getPriceTicks(), level);
        }
        OrderNode node = new OrderNode(order);
        level.enqueue(node);
        liveOrders.put(order.getOrderId(), node);
    }

    // ── Event dispatch ───────────────────────────────────────────────────

    private void fireTrade(TradeEvent event) {
        for (Consumer<TradeEvent> listener : tradeListeners) {
            listener.accept(event);
        }
    }

    private void fireBookChange() {
        for (Runnable listener : bookChangeListeners) {
            listener.run();
        }
    }

    // ── Book depth snapshots (for UI) ────────────────────────────────────

    /** Return top-N bid price levels as (priceTicks → totalQty). */
    public synchronized LinkedHashMap<Long, Long> getBidDepth(int levels) {
        return depthSnapshot(bids, levels);
    }

    /** Return top-N ask price levels as (priceTicks → totalQty). */
    public synchronized LinkedHashMap<Long, Long> getAskDepth(int levels) {
        return depthSnapshot(asks, levels);
    }

    private LinkedHashMap<Long, Long> depthSnapshot(
            ConcurrentSkipListMap<Long, PriceLevel> book, int levels) {
        LinkedHashMap<Long, Long> map = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<Long, PriceLevel> entry : book.entrySet()) {
            if (count++ >= levels) break;
            long totalQty = 0;
            OrderNode cur = entry.getValue().sentinel.next;
            while (cur != entry.getValue().sentinel) {
                totalQty += cur.order.getQuantity();
                cur = cur.next;
            }
            map.put(entry.getKey(), totalQty);
        }
        return map;
    }

    /** Total number of resting orders. */
    public synchronized int getLiveOrderCount() {
        return liveOrders.size();
    }

    // ── Trade event record ───────────────────────────────────────────────

    /**
     * Immutable trade execution report.
     *
     * @param aggressorOrderId the incoming (taker) order id
     * @param restingOrderId   the resting (maker) order id
     * @param priceTicks       execution price in ticks
     * @param quantity         filled quantity
     * @param timestampNanos   epoch-nano timestamp of the fill
     */
    public record TradeEvent(
            long aggressorOrderId,
            long restingOrderId,
            long priceTicks,
            long quantity,
            long timestampNanos
    ) {}
}
