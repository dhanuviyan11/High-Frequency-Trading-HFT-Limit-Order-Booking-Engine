package com.hft.engine.engine;

import com.hft.engine.engine.MatchingEngine.TradeEvent;
import com.hft.engine.model.Order;
import com.hft.engine.model.Side;
import javafx.application.Platform;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * High-throughput order generator for benchmarking the {@link MatchingEngine}.
 * <p>
 * Runs on a dedicated daemon thread, generating randomised buy/sell limit
 * orders, periodically cancelling older resting orders (to simulate realistic
 * market churn and keep memory bounded), and pushing them into the engine.
 * Metrics are batched and flushed to the JavaFX thread via {@link Platform#runLater}
 * at a fixed reporting interval to avoid flooding the FX event queue.
 */
public class BenchmarkSimulator {

    /** How often (ms) to flush metrics to the UI. */
    private static final long REPORT_INTERVAL_MS = 250;

    /** Target orders per second. */
    private static final int TARGET_OPS = 10_000;

    // ── Pricing parameters ───────────────────────────────────────────────
    /** Midpoint price in ticks ($100.00 = 10000). */
    private static final long MID_PRICE = 10_000L;
    /** Max spread from mid in ticks (±$2.00 = 200 ticks). */
    private static final int SPREAD_TICKS = 200;
    /** Min order quantity. */
    private static final long MIN_QTY = 1;
    /** Max order quantity. */
    private static final long MAX_QTY = 500;

    /** Capacity of recent order ID ring buffer for realistic cancellations. */
    private static final int CANCEL_RING_CAPACITY = 2048;

    // ── Metrics (written by generator thread, read by reporter) ──────────
    private final AtomicLong totalOrders = new AtomicLong(0);
    private final AtomicLong totalLatencyNanos = new AtomicLong(0);
    private final AtomicLong totalTrades = new AtomicLong(0);

    private final MatchingEngine engine;
    private final MetricsCallback metricsCallback;
    private final Consumer<TradeEvent> tradeListener = t -> totalTrades.incrementAndGet();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread generatorThread;

    // Snapshot values for delta calculation
    private long lastSnapshotOrders = 0;
    private long lastSnapshotLatency = 0;
    private long lastSnapshotTrades = 0;
    private long lastSnapshotTimeNanos = 0;
    private long benchmarkStartTimeNanos = 0;

    /**
     * Callback interface for pushing metrics to the UI.
     * Always invoked on the JavaFX Application Thread.
     */
    @FunctionalInterface
    public interface MetricsCallback {
        /**
         * @param ordersPerSec   orders processed per second (TPS)
         * @param avgLatencyUs   average execution latency in microseconds
         * @param totalOrders    cumulative orders submitted
         * @param totalTrades    cumulative trade fills
         * @param elapsedSec     seconds since benchmark started
         */
        void onMetrics(long ordersPerSec, double avgLatencyUs,
                       long totalOrders, long totalTrades, double elapsedSec);
    }

    public BenchmarkSimulator(MatchingEngine engine, MetricsCallback callback) {
        this.engine = engine;
        this.metricsCallback = callback;
    }

    /** Start the benchmark generator thread. Idempotent. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            totalOrders.set(0);
            totalLatencyNanos.set(0);
            totalTrades.set(0);
            lastSnapshotOrders = 0;
            lastSnapshotLatency = 0;
            lastSnapshotTrades = 0;
            long now = System.nanoTime();
            lastSnapshotTimeNanos = now;
            benchmarkStartTimeNanos = now;

            // Register lightweight trade counter
            engine.addTradeListener(tradeListener);

            generatorThread = new Thread(this::generatorLoop, "bench-generator");
            generatorThread.setDaemon(true);
            generatorThread.setPriority(Thread.NORM_PRIORITY);
            generatorThread.start();
        }
    }

    /** Stop the benchmark. Blocks until the generator thread exits. */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            engine.removeTradeListener(tradeListener);
            Thread t = generatorThread;
            if (t != null) {
                t.interrupt();
                try { t.join(2000); } catch (InterruptedException ignored) {}
            }
            generatorThread = null;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    // ── Generator loop ───────────────────────────────────────────────────

    private void generatorLoop() {
        final ThreadLocalRandom rng = ThreadLocalRandom.current();
        final long intervalNanos = 1_000_000_000L / TARGET_OPS;

        // Ring buffer to hold recent resting order IDs for realistic cancellations
        final long[] recentOrderIds = new long[CANCEL_RING_CAPACITY];
        int recentHead = 0;
        int recentCount = 0;

        long lastReportTime = System.nanoTime();
        long nextOrderTime = System.nanoTime();

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            // ── Pace to target rate ──────────────────────────────────────
            long now = System.nanoTime();
            if (now < nextOrderTime) {
                long diffNanos = nextOrderTime - now;
                if (diffNanos > 2_000_000L) {
                    try {
                        Thread.sleep(diffNanos / 1_000_000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    Thread.onSpinWait();
                }
                continue;
            }
            nextOrderTime = now + intervalNanos;

            // ── Realistic algorithmic cancellation (~25% of operations) ──
            if (recentCount > 0 && rng.nextInt(100) < 25) {
                int idx = rng.nextInt(recentCount);
                int slot = (recentHead - recentCount + idx + CANCEL_RING_CAPACITY) % CANCEL_RING_CAPACITY;
                long cancelId = recentOrderIds[slot];
                if (cancelId > 0) {
                    engine.cancelOrder(cancelId);
                    recentOrderIds[slot] = 0;
                }
            }

            // ── Generate random limit order ──────────────────────────────
            Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
            long price = MID_PRICE + rng.nextLong(-SPREAD_TICKS, SPREAD_TICKS + 1);
            long qty = rng.nextLong(MIN_QTY, MAX_QTY + 1);

            // ── Measure latency ──────────────────────────────────────────
            long t0 = System.nanoTime();
            Order order = engine.submitOrder(side, price, qty);
            long elapsed = System.nanoTime() - t0;

            // Track resting order for cancellation to keep memory bounded
            if (order.getQuantity() > 0) {
                recentOrderIds[recentHead] = order.getOrderId();
                recentHead = (recentHead + 1) % CANCEL_RING_CAPACITY;
                if (recentCount < CANCEL_RING_CAPACITY) {
                    recentCount++;
                }
            }

            totalOrders.incrementAndGet();
            totalLatencyNanos.addAndGet(elapsed);

            // ── Report metrics at interval ───────────────────────────────
            if (now - lastReportTime >= REPORT_INTERVAL_MS * 1_000_000L) {
                flushMetrics();
                lastReportTime = now;
            }
        }

        // Final flush
        flushMetrics();
    }

    private void flushMetrics() {
        long nowNanos = System.nanoTime();
        long curOrders = totalOrders.get();
        long curLatency = totalLatencyNanos.get();
        long curTrades = totalTrades.get();

        long deltaOrders = curOrders - lastSnapshotOrders;
        long deltaLatency = curLatency - lastSnapshotLatency;
        double deltaSec = (nowNanos - lastSnapshotTimeNanos) / 1_000_000_000.0;

        long ordersPerSec = deltaSec > 0 ? Math.round(deltaOrders / deltaSec) : 0;
        double avgLatencyUs = deltaOrders > 0
                ? (deltaLatency / (double) deltaOrders) / 1_000.0
                : 0.0;

        double totalElapsedSec = benchmarkStartTimeNanos > 0
                ? (nowNanos - benchmarkStartTimeNanos) / 1_000_000_000.0
                : 0.0;

        final long ops = ordersPerSec;
        final double lat = avgLatencyUs;
        final long tot = curOrders;
        final long trades = curTrades;
        final double elapsed = totalElapsedSec;

        lastSnapshotOrders = curOrders;
        lastSnapshotLatency = curLatency;
        lastSnapshotTrades = curTrades;
        lastSnapshotTimeNanos = nowNanos;

        Platform.runLater(() -> metricsCallback.onMetrics(ops, lat, tot, trades, elapsed));
    }
}
