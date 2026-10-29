package com.hft.engine;

import com.hft.engine.engine.MatchingEngine;
import com.hft.engine.engine.MatchingEngine.TradeEvent;
import com.hft.engine.model.Order;
import com.hft.engine.model.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MatchingEngineTest {

    private MatchingEngine engine;
    private List<TradeEvent> trades;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        trades = new ArrayList<>();
        engine.addTradeListener(trades::add);
    }

    @Test
    @DisplayName("Resting BUY order is placed on bids book when no asks exist")
    void testRestingBuyOrder() {
        Order order = engine.submitOrder(Side.BUY, 10000, 50);

        assertEquals(50, order.getQuantity());
        assertEquals(1, engine.getLiveOrderCount());
        assertTrue(trades.isEmpty());

        var bidDepth = engine.getBidDepth(10);
        assertEquals(1, bidDepth.size());
        assertEquals(50L, bidDepth.get(10000L));
    }

    @Test
    @DisplayName("Resting SELL order is placed on asks book when no bids exist")
    void testRestingSellOrder() {
        Order order = engine.submitOrder(Side.SELL, 10500, 100);

        assertEquals(100, order.getQuantity());
        assertEquals(1, engine.getLiveOrderCount());
        assertTrue(trades.isEmpty());

        var askDepth = engine.getAskDepth(10);
        assertEquals(1, askDepth.size());
        assertEquals(100L, askDepth.get(10500L));
    }

    @Test
    @DisplayName("Exact match between BUY and SELL")
    void testExactMatch() {
        Order sell = engine.submitOrder(Side.SELL, 10000, 100);
        Order buy = engine.submitOrder(Side.BUY, 10000, 100);

        assertEquals(0, buy.getQuantity());
        assertEquals(0, sell.getQuantity());
        assertEquals(0, engine.getLiveOrderCount());
        assertEquals(1, trades.size());

        TradeEvent trade = trades.getFirst();
        assertEquals(buy.getOrderId(), trade.aggressorOrderId());
        assertEquals(sell.getOrderId(), trade.restingOrderId());
        assertEquals(10000, trade.priceTicks());
        assertEquals(100, trade.quantity());
    }

    @Test
    @DisplayName("Partial fill: Aggressor order larger than resting maker order")
    void testPartialFillAggressorLarger() {
        Order sell = engine.submitOrder(Side.SELL, 10000, 40);
        Order buy = engine.submitOrder(Side.BUY, 10000, 100);

        assertEquals(0, sell.getQuantity());
        assertEquals(60, buy.getQuantity());
        assertEquals(1, engine.getLiveOrderCount());
        assertEquals(1, trades.size());

        TradeEvent trade = trades.getFirst();
        assertEquals(40, trade.quantity());
        assertEquals(10000, trade.priceTicks());

        var bidDepth = engine.getBidDepth(10);
        assertEquals(60L, bidDepth.get(10000L));
    }

    @Test
    @DisplayName("Partial fill: Resting maker order larger than aggressor order")
    void testPartialFillMakerLarger() {
        Order sell = engine.submitOrder(Side.SELL, 10000, 100);
        Order buy = engine.submitOrder(Side.BUY, 10000, 30);

        assertEquals(0, buy.getQuantity());
        assertEquals(70, sell.getQuantity());
        assertEquals(1, engine.getLiveOrderCount());
        assertEquals(1, trades.size());

        TradeEvent trade = trades.getFirst();
        assertEquals(30, trade.quantity());
        assertEquals(10000, trade.priceTicks());

        var askDepth = engine.getAskDepth(10);
        assertEquals(70L, askDepth.get(10000L));
    }

    @Test
    @DisplayName("Price-Time Priority (FIFO) at same price level")
    void testPriceTimePriorityFIFO() {
        Order sell1 = engine.submitOrder(Side.SELL, 10000, 50);
        Order sell2 = engine.submitOrder(Side.SELL, 10000, 50);

        Order buy = engine.submitOrder(Side.BUY, 10000, 60);

        assertEquals(0, buy.getQuantity());
        assertEquals(0, sell1.getQuantity());
        assertEquals(40, sell2.getQuantity());
        assertEquals(1, engine.getLiveOrderCount());
        assertEquals(2, trades.size());

        assertEquals(sell1.getOrderId(), trades.get(0).restingOrderId());
        assertEquals(50, trades.get(0).quantity());

        assertEquals(sell2.getOrderId(), trades.get(1).restingOrderId());
        assertEquals(10, trades.get(1).quantity());
    }

    @Test
    @DisplayName("Price priority: Aggressor matches best price first across multiple levels")
    void testPricePriorityMultipleLevels() {
        Order sellHigh = engine.submitOrder(Side.SELL, 10200, 50);
        Order sellLow = engine.submitOrder(Side.SELL, 10100, 50);

        Order buy = engine.submitOrder(Side.BUY, 10300, 80);

        assertEquals(0, buy.getQuantity());
        assertEquals(2, trades.size());

        assertEquals(sellLow.getOrderId(), trades.get(0).restingOrderId());
        assertEquals(10100, trades.get(0).priceTicks());
        assertEquals(50, trades.get(0).quantity());

        assertEquals(sellHigh.getOrderId(), trades.get(1).restingOrderId());
        assertEquals(10200, trades.get(1).priceTicks());
        assertEquals(30, trades.get(1).quantity());

        assertEquals(20, sellHigh.getQuantity());
        assertEquals(1, engine.getLiveOrderCount());
    }

    @Test
    @DisplayName("Cancel order removes resting order from book and live orders map")
    void testCancelOrder() {
        Order sell = engine.submitOrder(Side.SELL, 10000, 100);
        assertEquals(1, engine.getLiveOrderCount());

        boolean cancelled = engine.cancelOrder(sell.getOrderId());
        assertTrue(cancelled);
        assertEquals(0, engine.getLiveOrderCount());

        var askDepth = engine.getAskDepth(10);
        assertTrue(askDepth.isEmpty());

        assertFalse(engine.cancelOrder(sell.getOrderId()));
    }

    @Test
    @DisplayName("Cancelling head of price level preserves next node as new head")
    void testCancelHeadOrder() {
        Order s1 = engine.submitOrder(Side.SELL, 10000, 10);
        Order s2 = engine.submitOrder(Side.SELL, 10000, 20);

        assertTrue(engine.cancelOrder(s1.getOrderId()));
        assertEquals(1, engine.getLiveOrderCount());

        Order buy = engine.submitOrder(Side.BUY, 10000, 20);
        assertEquals(0, buy.getQuantity());
        assertEquals(1, trades.size());
        assertEquals(s2.getOrderId(), trades.getFirst().restingOrderId());
    }

    @Test
    @DisplayName("Cancelling tail of price level preserves FIFO queue")
    void testCancelTailOrder() {
        Order s1 = engine.submitOrder(Side.SELL, 10000, 10);
        Order s2 = engine.submitOrder(Side.SELL, 10000, 20);

        assertTrue(engine.cancelOrder(s2.getOrderId()));
        assertEquals(1, engine.getLiveOrderCount());

        Order buy = engine.submitOrder(Side.BUY, 10000, 10);
        assertEquals(0, buy.getQuantity());
        assertEquals(1, trades.size());
        assertEquals(s1.getOrderId(), trades.getFirst().restingOrderId());
    }

    @Test
    @DisplayName("Cancelling middle order in FIFO queue preserves queue links")
    void testCancelMiddleOrder() {
        Order s1 = engine.submitOrder(Side.SELL, 10000, 10);
        Order s2 = engine.submitOrder(Side.SELL, 10000, 20);
        Order s3 = engine.submitOrder(Side.SELL, 10000, 30);

        assertTrue(engine.cancelOrder(s2.getOrderId()));
        assertEquals(2, engine.getLiveOrderCount());

        Order buy = engine.submitOrder(Side.BUY, 10000, 30);
        assertEquals(0, buy.getQuantity());
        assertEquals(2, trades.size());

        assertEquals(s1.getOrderId(), trades.get(0).restingOrderId());
        assertEquals(10, trades.get(0).quantity());

        assertEquals(s3.getOrderId(), trades.get(1).restingOrderId());
        assertEquals(20, trades.get(1).quantity());
        assertEquals(10, s3.getQuantity());
    }

    @Test
    @DisplayName("Invalid inputs are strictly rejected with IllegalArgumentException")
    void testInvalidInputsRejected() {
        assertThrows(IllegalArgumentException.class, () -> engine.submitOrder(null, 10000, 10));
        assertThrows(IllegalArgumentException.class, () -> engine.submitOrder(Side.BUY, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> engine.submitOrder(Side.BUY, -500, 10));
        assertThrows(IllegalArgumentException.class, () -> engine.submitOrder(Side.BUY, 10000, 0));
        assertThrows(IllegalArgumentException.class, () -> engine.submitOrder(Side.BUY, 10000, -10));
    }

    @Test
    @DisplayName("Removing trade listener prevents further notifications")
    void testRemoveTradeListener() {
        List<TradeEvent> extraList = new ArrayList<>();
        Consumer<TradeEvent> extraListener = extraList::add;

        engine.addTradeListener(extraListener);
        engine.submitOrder(Side.SELL, 10000, 10);
        engine.submitOrder(Side.BUY, 10000, 10);
        assertEquals(1, extraList.size());

        engine.removeTradeListener(extraListener);
        engine.submitOrder(Side.SELL, 10000, 10);
        engine.submitOrder(Side.BUY, 10000, 10);
        // Extra listener should not receive subsequent trades
        assertEquals(1, extraList.size());
    }

    @Test
    @DisplayName("Price parsing and tick formatting correctly handle edge cases")
    void testPriceParsingAndFormatting() {
        assertEquals(50L, HftEngineApplication.parsePriceTicks(".50"));
        assertEquals(50L, HftEngineApplication.parsePriceTicks(".5"));
        assertEquals(10050L, HftEngineApplication.parsePriceTicks("100.5"));
        assertEquals(10005L, HftEngineApplication.parsePriceTicks("100.05"));
        assertEquals(10000L, HftEngineApplication.parsePriceTicks("100"));
        assertEquals(10000L, HftEngineApplication.parsePriceTicks("100."));
        assertEquals(-550L, HftEngineApplication.parsePriceTicks("-5.50"));
        assertEquals(-50L, HftEngineApplication.parsePriceTicks("-0.50"));

        assertEquals("100.50", HftEngineApplication.formatTicks(10050L));
        assertEquals("0.05", HftEngineApplication.formatTicks(5L));
        assertEquals("-5.50", HftEngineApplication.formatTicks(-550L));
    }
}
