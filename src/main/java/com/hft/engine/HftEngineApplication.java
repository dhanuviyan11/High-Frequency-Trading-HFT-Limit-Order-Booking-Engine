package com.hft.engine;

import com.hft.engine.engine.BenchmarkSimulator;
import com.hft.engine.engine.MatchingEngine;
import com.hft.engine.engine.MatchingEngine.TradeEvent;
import com.hft.engine.model.Side;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.effect.Glow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * HFT Matching Engine — Cinematic JavaFX visualization.
 * <p>
 * Bloomberg-terminal inspired dark UI with:
 * <ul>
 *   <li>Scrolling market ticker strip</li>
 *   <li>Real-time Canvas price chart with zero-allocation ring buffer</li>
 *   <li>Lock-free trade event queue decouples engine hot path from UI</li>
 *   <li>Animated depth heatmap bars (bids green, asks red)</li>
 *   <li>High-throughput trade tape</li>
 *   <li>Live performance metrics dashboard</li>
 * </ul>
 */
@SpringBootApplication
public class HftEngineApplication extends Application {

    private ConfigurableApplicationContext springContext;
    private MatchingEngine engine;

    // ── UI Controls ──────────────────────────────────────────────────────
    private TextField priceField;
    private TextField quantityField;
    private ToggleGroup sideToggle;
    private TextArea logConsole;

    // ── Benchmark ────────────────────────────────────────────────────────
    private volatile BenchmarkSimulator benchmarkSimulator;
    private Button benchmarkBtn;
    private Label tpsLabel, latencyLabel, totalOrdersLabel, totalTradesLabel;
    private Label benchStatusLabel;

    // ── Live Price Chart (Zero-allocation circular buffer) ────────────────
    private Canvas priceChartCanvas;
    private static final int MAX_PRICE_POINTS = 400;
    private final double[] priceHistory = new double[MAX_PRICE_POINTS];
    private int priceHistoryCount = 0;
    private int priceHistoryHead = 0;

    private volatile double lastTradePrice = 100.0;
    private volatile double previousPrice = 100.0;
    private double priceHigh = 102.0;
    private double priceLow = 98.0;

    // ── Depth Visualization ──────────────────────────────────────────────
    private Canvas depthCanvas;

    // ── Trade Tape & Decoupled Queue ─────────────────────────────────────
    private HBox tradeTapeContainer;
    private static final int MAX_TAPE_TRADES = 50;
    /** Lock-free queue decoupling matching engine hot-path from UI thread */
    private final ConcurrentLinkedQueue<TradeEvent> tradeQueue = new ConcurrentLinkedQueue<>();

    // ── Ticker Strip ─────────────────────────────────────────────────────
    private Label tickerLabel;
    private double tickerOffset = 0;

    // ── Price Display ────────────────────────────────────────────────────
    private Label lastPriceLabel;
    private Label priceChangeLabel;

    // Static reusable effects to eliminate native Direct3D memory leaks
    private static final Glow STATIC_GLOW = new Glow(0.4);

    // ── Animation ────────────────────────────────────────────────────────
    private AnimationTimer renderLoop;
    private long lastDepthRefresh = 0;
    private static final long DEPTH_REFRESH_NS = 100_000_000L; // 100ms

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    // ── Colors ───────────────────────────────────────────────────────────
    private static final Color BG_DARK       = Color.web("#0a0e14");
    private static final Color BG_PANEL      = Color.web("#111820");
    private static final Color BG_CARD       = Color.web("#0d1117");
    private static final Color BORDER        = Color.web("#1c2333");
    private static final Color TEXT_PRIMARY   = Color.web("#c9d1d9");
    private static final Color TEXT_DIM       = Color.web("#484f58");
    private static final Color GREEN_BID      = Color.web("#00d68f");
    private static final Color GREEN_BRIGHT   = Color.web("#3cfca2");
    private static final Color RED_ASK        = Color.web("#ff6b6b");
    private static final Color RED_BRIGHT     = Color.web("#ff3860");
    private static final Color BLUE_ACCENT    = Color.web("#58a6ff");
    private static final Color PURPLE_ACCENT  = Color.web("#bc8cff");
    private static final Color ORANGE_ACCENT  = Color.web("#f0883e");
    private static final Color CHART_LINE     = Color.web("#58a6ff");
    private static final Color CHART_GLOW     = Color.web("#58a6ff", 0.3);
    private static final Color CHART_GRID     = Color.web("#1c2333", 0.5);

    // ── JavaFX Lifecycle ─────────────────────────────────────────────────

    @Override
    public void init() {
        springContext = new SpringApplicationBuilder(HftEngineApplication.class)
                .web(WebApplicationType.NONE)
                .headless(false)
                .run();
        engine = springContext.getBean(MatchingEngine.class);
        engine.addTradeListener(this::onTrade);
        engine.addBookChangeListener(this::onBookChange);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("⚡ HFT Order Matching Engine — Live Market View");

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #0a0e14;");

        // ── Top: Ticker strip + header ───────────────────────────────────
        VBox topSection = new VBox(0,
                createTickerStrip(),
                createHeader()
        );
        root.setTop(topSection);

        // ── Center: Chart + Depth + Trade Tape ───────────────────────────
        VBox centerSection = new VBox(8,
                createPriceChartPanel(),
                createMiddleRow(),
                createTradeTape()
        );
        centerSection.setPadding(new Insets(8, 16, 0, 16));
        VBox.setVgrow(centerSection.getChildren().get(0), Priority.ALWAYS);
        root.setCenter(centerSection);

        // ── Bottom: Controls ─────────────────────────────────────────────
        VBox bottomSection = new VBox(6,
                createBenchmarkPanel(),
                createOrderEntry(),
                createLogConsole()
        );
        bottomSection.setPadding(new Insets(6, 16, 12, 16));
        root.setBottom(bottomSection);

        Scene scene = new Scene(root, 1280, 900);
        scene.setFill(BG_DARK);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        // Initialize price point
        addPricePoint(lastTradePrice);

        // Start animation loop
        startRenderLoop();
        startTickerAnimation();

        appendLog("ENGINE", "Matching engine initialized. Live market view active.");
    }

    @Override
    public void stop() {
        shutdown();
    }

    private void shutdown() {
        if (renderLoop != null) renderLoop.stop();
        if (benchmarkSimulator != null) benchmarkSimulator.stop();
        if (springContext != null && springContext.isActive()) springContext.close();
        Platform.exit();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SCROLLING TICKER STRIP (CNBC / Bloomberg style)
    // ══════════════════════════════════════════════════════════════════════

    private HBox createTickerStrip() {
        tickerLabel = new Label();
        tickerLabel.setStyle("-fx-text-fill: #58a6ff; -fx-font-family: 'Consolas'; -fx-font-size: 12px;");
        updateTickerText();

        HBox strip = new HBox(tickerLabel);
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.setPadding(new Insets(4, 0, 4, 0));
        strip.setStyle("-fx-background-color: linear-gradient(to right, #0d1117, #111820, #0d1117); " +
                "-fx-border-color: #1c2333; -fx-border-width: 0 0 1 0;");
        strip.setClip(new javafx.scene.shape.Rectangle(2000, 30));
        return strip;
    }

    private void updateTickerText() {
        double current = lastTradePrice;
        double prev = previousPrice;
        String arrow = current >= prev ? "▲" : "▼";
        double change = current - prev;
        double changePct = prev > 0 ? (change / prev) * 100.0 : 0.0;

        StringBuilder sb = new StringBuilder();
        String[] symbols = {"AAPL", "MSFT", "GOOG", "AMZN", "NVDA", "META", "TSLA", "JPM"};
        Random rng = new Random(System.currentTimeMillis() / 5000);
        for (String sym : symbols) {
            double p = 100 + rng.nextDouble() * 200;
            double ch = (rng.nextDouble() - 0.48) * 5;
            String a = ch >= 0 ? "▲" : "▼";
            sb.append(String.format("  %s $%.2f %s%.2f (%.2f%%)  │", sym, p, a, Math.abs(ch), Math.abs(ch / p * 100)));
        }
        sb.append(String.format("  OME-SIM $%.2f %s%.2f (%.2f%%)  │", current, arrow, Math.abs(change), Math.abs(changePct)));
        String fullText = sb.toString();
        tickerLabel.setText(fullText + "    " + fullText + "    " + fullText);
    }

    private void startTickerAnimation() {
        Timeline tickerTimeline = new Timeline(new KeyFrame(Duration.millis(30), e -> {
            tickerOffset -= 1.5;
            if (tickerOffset < -1500) {
                tickerOffset = 0;
                updateTickerText();
            }
            tickerLabel.setTranslateX(tickerOffset);
        }));
        tickerTimeline.setCycleCount(Timeline.INDEFINITE);
        tickerTimeline.play();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HEADER with last price display
    // ══════════════════════════════════════════════════════════════════════

    private HBox createHeader() {
        Label title = new Label("⚡ HFT MATCHING ENGINE");
        title.setStyle("-fx-text-fill: #58a6ff; -fx-font-size: 20px; -fx-font-weight: bold;");
        title.setEffect(STATIC_GLOW);

        Label sub = new Label("LIMIT ORDER BOOK  •  LIVE");
        sub.setStyle("-fx-text-fill: #484f58; -fx-font-size: 11px; -fx-font-weight: bold; -fx-font-family: 'Consolas';");

        VBox titleBox = new VBox(2, title, sub);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        lastPriceLabel = new Label("$100.00");
        lastPriceLabel.setStyle("-fx-text-fill: #00d68f; -fx-font-size: 28px; -fx-font-weight: bold; " +
                "-fx-font-family: 'Consolas';");
        lastPriceLabel.setEffect(STATIC_GLOW);

        priceChangeLabel = new Label("+0.00 (+0.00%)");
        priceChangeLabel.setStyle("-fx-text-fill: #484f58; -fx-font-size: 12px; -fx-font-family: 'Consolas';");

        VBox priceBox = new VBox(0, lastPriceLabel, priceChangeLabel);
        priceBox.setAlignment(Pos.CENTER_RIGHT);

        Label liveIndicator = new Label("● REC");
        liveIndicator.setStyle("-fx-text-fill: #ff3860; -fx-font-size: 11px; -fx-font-weight: bold;");

        FadeTransition blink = new FadeTransition(Duration.millis(600), liveIndicator);
        blink.setFromValue(1.0);
        blink.setToValue(0.2);
        blink.setCycleCount(FadeTransition.INDEFINITE);
        blink.setAutoReverse(true);
        blink.play();

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(16, titleBox, spacer, liveIndicator, priceBox);
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(10, 20, 10, 20));
        header.setStyle("-fx-background-color: #111820; -fx-border-color: #1c2333; -fx-border-width: 0 0 1 0;");
        return header;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  REAL-TIME PRICE CHART (Zero-Allocation Circular Buffer)
    // ══════════════════════════════════════════════════════════════════════

    private StackPane createPriceChartPanel() {
        priceChartCanvas = new Canvas(900, 200);

        StackPane chartPane = new StackPane(priceChartCanvas);
        chartPane.setStyle("-fx-background-color: #0d1117; -fx-background-radius: 8; " +
                "-fx-border-color: #1c2333; -fx-border-radius: 8;");
        chartPane.setPadding(new Insets(8));
        chartPane.setMinHeight(180);
        chartPane.setPrefHeight(220);

        chartPane.widthProperty().addListener((obs, o, n) -> priceChartCanvas.setWidth(n.doubleValue() - 16));
        chartPane.heightProperty().addListener((obs, o, n) -> priceChartCanvas.setHeight(n.doubleValue() - 16));

        return chartPane;
    }

    private void addPricePoint(double price) {
        priceHistory[priceHistoryHead] = price;
        priceHistoryHead = (priceHistoryHead + 1) % MAX_PRICE_POINTS;
        if (priceHistoryCount < MAX_PRICE_POINTS) {
            priceHistoryCount++;
        }
    }

    private double getPricePoint(int i) {
        int idx = (priceHistoryHead - priceHistoryCount + i + MAX_PRICE_POINTS) % MAX_PRICE_POINTS;
        return priceHistory[idx];
    }

    private void renderPriceChart() {
        GraphicsContext gc = priceChartCanvas.getGraphicsContext2D();
        double w = priceChartCanvas.getWidth();
        double h = priceChartCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        // Clear
        gc.setFill(BG_CARD);
        gc.fillRect(0, 0, w, h);

        // Grid lines
        gc.setStroke(CHART_GRID);
        gc.setLineWidth(0.5);
        for (int i = 1; i < 5; i++) {
            double y = h * i / 5.0;
            gc.strokeLine(0, y, w, y);
        }
        for (int i = 1; i < 8; i++) {
            double x = w * i / 8.0;
            gc.strokeLine(x, 0, x, h);
        }

        // Price labels on right
        gc.setFill(TEXT_DIM);
        gc.setFont(Font.font("Consolas", 10));
        double range = priceHigh - priceLow;
        if (range < 0.01) range = 1.0;
        for (int i = 0; i <= 4; i++) {
            double price = priceHigh - (range * i / 4.0);
            double y = h * i / 4.0;
            gc.fillText(String.format("$%.2f", price), w - 55, y + 12);
        }

        int count = priceHistoryCount;
        if (count < 2) return;
        double step = w / (double) MAX_PRICE_POINTS;

        // Glow trail (wider, semi-transparent)
        gc.setStroke(CHART_GLOW);
        gc.setLineWidth(4.0);
        gc.beginPath();
        for (int i = 0; i < count; i++) {
            double p = getPricePoint(i);
            double x = (MAX_PRICE_POINTS - count + i) * step;
            double y = h - ((p - priceLow) / range) * (h - 20) - 10;
            if (i == 0) gc.moveTo(x, y);
            else gc.lineTo(x, y);
        }
        gc.stroke();

        // Main line
        gc.setStroke(CHART_LINE);
        gc.setLineWidth(1.8);
        gc.beginPath();
        for (int i = 0; i < count; i++) {
            double p = getPricePoint(i);
            double x = (MAX_PRICE_POINTS - count + i) * step;
            double y = h - ((p - priceLow) / range) * (h - 20) - 10;
            if (i == 0) gc.moveTo(x, y);
            else gc.lineTo(x, y);
        }
        gc.stroke();

        // Dot at current price
        if (count > 0) {
            double latest = getPricePoint(count - 1);
            double cx = (MAX_PRICE_POINTS - 1) * step;
            double cy = h - ((latest - priceLow) / range) * (h - 20) - 10;
            gc.setFill(Color.WHITE);
            gc.fillOval(cx - 3, cy - 3, 6, 6);
            gc.setFill(CHART_LINE);
            gc.fillOval(cx - 2, cy - 2, 4, 4);
        }

        // Fill area under curve
        gc.setFill(Color.web("#58a6ff", 0.06));
        gc.beginPath();
        gc.moveTo((MAX_PRICE_POINTS - count) * step, h);
        for (int i = 0; i < count; i++) {
            double p = getPricePoint(i);
            double x = (MAX_PRICE_POINTS - count + i) * step;
            double y = h - ((p - priceLow) / range) * (h - 20) - 10;
            gc.lineTo(x, y);
        }
        gc.lineTo((MAX_PRICE_POINTS - 1) * step, h);
        gc.closePath();
        gc.fill();

        // Chart label
        gc.setFill(TEXT_DIM);
        gc.setFont(Font.font("Consolas", FontWeight.BOLD, 11));
        gc.fillText("PRICE (LIVE)", 8, 16);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DEPTH HEATMAP + TRADE TAPE
    // ══════════════════════════════════════════════════════════════════════

    private HBox createMiddleRow() {
        depthCanvas = new Canvas(500, 180);
        StackPane depthPane = new StackPane(depthCanvas);
        depthPane.setStyle("-fx-background-color: #0d1117; -fx-background-radius: 8; " +
                "-fx-border-color: #1c2333; -fx-border-radius: 8;");
        depthPane.setPadding(new Insets(8));
        depthPane.widthProperty().addListener((obs, o, n) -> depthCanvas.setWidth(n.doubleValue() - 16));
        depthPane.heightProperty().addListener((obs, o, n) -> depthCanvas.setHeight(n.doubleValue() - 16));

        HBox row = new HBox(8, depthPane);
        HBox.setHgrow(depthPane, Priority.ALWAYS);
        row.setMinHeight(180);
        row.setPrefHeight(200);
        return row;
    }

    private void renderDepthChart() {
        GraphicsContext gc = depthCanvas.getGraphicsContext2D();
        double w = depthCanvas.getWidth();
        double h = depthCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        gc.setFill(BG_CARD);
        gc.fillRect(0, 0, w, h);

        LinkedHashMap<Long, Long> bidDepth = engine.getBidDepth(10);
        LinkedHashMap<Long, Long> askDepth = engine.getAskDepth(10);

        long maxQty = 1;
        for (long q : bidDepth.values()) maxQty = Math.max(maxQty, q);
        for (long q : askDepth.values()) maxQty = Math.max(maxQty, q);

        double midX = w / 2.0;
        double barHeight = h / 11.0;
        double maxBarWidth = midX - 60;

        gc.setFill(TEXT_DIM);
        gc.setFont(Font.font("Consolas", FontWeight.BOLD, 11));
        gc.fillText("BIDS", 8, 14);
        gc.fillText("ASKS", midX + 8, 14);

        gc.setStroke(BORDER);
        gc.setLineWidth(1);
        gc.strokeLine(midX, 0, midX, h);

        int row = 0;
        for (Map.Entry<Long, Long> e : bidDepth.entrySet()) {
            double y = 20 + row * barHeight;
            double barW = (e.getValue() / (double) maxQty) * maxBarWidth;
            double intensity = Math.min(1.0, e.getValue() / (double) maxQty);

            gc.setFill(Color.web("#00d68f", 0.15 + intensity * 0.5));
            gc.fillRect(midX - barW - 4, y, barW, barHeight - 2);

            gc.setFill(GREEN_BID);
            gc.fillRect(midX - 5, y, 2, barHeight - 2);

            gc.setFill(GREEN_BID);
            gc.setFont(Font.font("Consolas", FontWeight.BOLD, 11));
            gc.fillText(formatTicks(e.getKey()), midX - barW - 54, y + barHeight / 2 + 3);

            gc.setFill(TEXT_PRIMARY);
            gc.setFont(Font.font("Consolas", 10));
            gc.fillText(String.format("%,d", e.getValue()), midX - barW + 4, y + barHeight / 2 + 3);

            row++;
        }

        row = 0;
        for (Map.Entry<Long, Long> e : askDepth.entrySet()) {
            double y = 20 + row * barHeight;
            double barW = (e.getValue() / (double) maxQty) * maxBarWidth;
            double intensity = Math.min(1.0, e.getValue() / (double) maxQty);

            gc.setFill(Color.web("#ff6b6b", 0.15 + intensity * 0.5));
            gc.fillRect(midX + 4, y, barW, barHeight - 2);

            gc.setFill(RED_ASK);
            gc.fillRect(midX + 3, y, 2, barHeight - 2);

            gc.setFill(RED_ASK);
            gc.setFont(Font.font("Consolas", FontWeight.BOLD, 11));
            gc.fillText(formatTicks(e.getKey()), midX + barW + 8, y + barHeight / 2 + 3);

            gc.setFill(TEXT_PRIMARY);
            gc.setFont(Font.font("Consolas", 10));
            gc.fillText(String.format("%,d", e.getValue()), midX + 8, y + barHeight / 2 + 3);

            row++;
        }

        if (!bidDepth.isEmpty() && !askDepth.isEmpty()) {
            long bestBid = bidDepth.keySet().iterator().next();
            long bestAsk = askDepth.keySet().iterator().next();
            long spread = bestAsk - bestBid;
            gc.setFill(PURPLE_ACCENT);
            gc.setFont(Font.font("Consolas", FontWeight.BOLD, 10));
            gc.fillText("SPREAD: " + formatTicks(spread), midX - 35, h - 4);
        }
    }

    // ── Trade Tape ───────────────────────────────────────────────────────

    private VBox createTradeTape() {
        tradeTapeContainer = new HBox(4);
        tradeTapeContainer.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroll = new ScrollPane(tradeTapeContainer);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        scroll.setFitToHeight(true);
        scroll.setPrefHeight(32);

        Label tapeHeader = new Label("TRADE TAPE");
        tapeHeader.setStyle("-fx-text-fill: #484f58; -fx-font-size: 10px; -fx-font-weight: bold; -fx-font-family: 'Consolas';");

        VBox tapeBox = new VBox(2, tapeHeader, scroll);
        tapeBox.setPadding(new Insets(4, 8, 4, 8));
        tapeBox.setStyle("-fx-background-color: #0d1117; -fx-background-radius: 6; " +
                "-fx-border-color: #1c2333; -fx-border-radius: 6;");
        tapeBox.setMaxHeight(55);
        return tapeBox;
    }

    /** Appends a trade badge to the tape on the JavaFX Application Thread. */
    private void addTradeToTape(TradeEvent trade) {
        double price = trade.priceTicks() / 100.0;
        boolean isUp = price >= previousPrice;
        String color = isUp ? "#00d68f" : "#ff6b6b";
        String arrow = isUp ? "▲" : "▼";

        Label tradeLabel = new Label(String.format("%s$%.2f×%d", arrow, price, trade.quantity()));
        tradeLabel.setStyle("-fx-text-fill: " + color + "; -fx-font-family: 'Consolas'; " +
                "-fx-font-size: 11px; -fx-font-weight: bold; " +
                "-fx-background-color: " + (isUp ? "#00d68f15" : "#ff6b6b15") + "; " +
                "-fx-background-radius: 3; -fx-padding: 2 6;");

        tradeTapeContainer.getChildren().addFirst(tradeLabel);
        if (tradeTapeContainer.getChildren().size() > MAX_TAPE_TRADES) {
            tradeTapeContainer.getChildren().removeLast();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BENCHMARK PANEL
    // ══════════════════════════════════════════════════════════════════════

    private HBox createBenchmarkPanel() {
        benchmarkBtn = new Button("▶  START BENCHMARK");
        benchmarkBtn.setStyle(
                "-fx-background-color: #1f6feb; -fx-text-fill: white; " +
                "-fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 6 18; " +
                "-fx-background-radius: 6; -fx-cursor: hand; -fx-font-family: 'Consolas';");
        benchmarkBtn.setOnAction(e -> onToggleBenchmark());

        benchStatusLabel = new Label("IDLE");
        benchStatusLabel.setStyle("-fx-text-fill: #484f58; -fx-font-size: 10px; -fx-font-weight: bold; -fx-font-family: 'Consolas';");

        VBox ctrlBox = new VBox(4, benchmarkBtn, benchStatusLabel);
        ctrlBox.setAlignment(Pos.CENTER);
        ctrlBox.setPrefWidth(170);

        tpsLabel = metricVal("0");
        VBox tpsCard = metricCard("⚡ TPS", tpsLabel, "#58a6ff");

        latencyLabel = metricVal("0.00");
        VBox latCard = metricCard("⏱ LATENCY μs", latencyLabel, "#bc8cff");

        totalOrdersLabel = metricVal("0");
        VBox ordCard = metricCard("📦 ORDERS", totalOrdersLabel, "#00d68f");

        totalTradesLabel = metricVal("0");
        VBox trdCard = metricCard("🔄 TRADES", totalTradesLabel, "#f0883e");

        HBox metrics = new HBox(8, tpsCard, latCard, ordCard, trdCard);
        HBox.setHgrow(tpsCard, Priority.ALWAYS);
        HBox.setHgrow(latCard, Priority.ALWAYS);
        HBox.setHgrow(ordCard, Priority.ALWAYS);
        HBox.setHgrow(trdCard, Priority.ALWAYS);

        HBox panel = new HBox(12, ctrlBox, metrics);
        HBox.setHgrow(metrics, Priority.ALWAYS);
        panel.setAlignment(Pos.CENTER_LEFT);
        panel.setPadding(new Insets(8, 12, 8, 12));
        panel.setStyle("-fx-background-color: #111820; -fx-background-radius: 8; " +
                "-fx-border-color: #1c2333; -fx-border-radius: 8;");
        return panel;
    }

    private VBox metricCard(String title, Label val, String color) {
        Label t = new Label(title);
        t.setStyle("-fx-text-fill: #484f58; -fx-font-size: 9px; -fx-font-family: 'Consolas'; -fx-font-weight: bold;");
        val.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 20px; -fx-font-weight: bold; -fx-font-family: 'Consolas';");
        VBox c = new VBox(1, t, val);
        c.setAlignment(Pos.CENTER);
        c.setPadding(new Insets(6, 10, 6, 10));
        c.setStyle("-fx-background-color: #0a0e14; -fx-background-radius: 6; -fx-border-color: #1c2333; -fx-border-radius: 6;");
        return c;
    }

    private Label metricVal(String init) {
        Label l = new Label(init);
        l.setMinWidth(70);
        l.setAlignment(Pos.CENTER);
        return l;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ORDER ENTRY
    // ══════════════════════════════════════════════════════════════════════

    private HBox createOrderEntry() {
        RadioButton buyBtn = new RadioButton("BUY");
        buyBtn.setStyle("-fx-text-fill: #00d68f; -fx-font-weight: bold; -fx-font-family: 'Consolas';");
        buyBtn.setUserData(Side.BUY);

        RadioButton sellBtn = new RadioButton("SELL");
        sellBtn.setStyle("-fx-text-fill: #ff6b6b; -fx-font-weight: bold; -fx-font-family: 'Consolas';");
        sellBtn.setUserData(Side.SELL);

        sideToggle = new ToggleGroup();
        buyBtn.setToggleGroup(sideToggle);
        sellBtn.setToggleGroup(sideToggle);
        buyBtn.setSelected(true);

        Label pLbl = new Label("PRICE:");
        pLbl.setStyle("-fx-text-fill: #484f58; -fx-font-family: 'Consolas'; -fx-font-size: 11px;");
        priceField = new TextField("100.00");
        priceField.setPrefWidth(100);
        priceField.setStyle(inputStyle());

        Label qLbl = new Label("QTY:");
        qLbl.setStyle("-fx-text-fill: #484f58; -fx-font-family: 'Consolas'; -fx-font-size: 11px;");
        quantityField = new TextField("100");
        quantityField.setPrefWidth(80);
        quantityField.setStyle(inputStyle());

        Button submitBtn = new Button("SUBMIT");
        submitBtn.setStyle("-fx-background-color: #238636; -fx-text-fill: white; -fx-font-weight: bold; " +
                "-fx-padding: 5 16; -fx-background-radius: 4; -fx-cursor: hand; -fx-font-family: 'Consolas'; -fx-font-size: 11px;");
        submitBtn.setOnAction(e -> onSubmitOrder());

        Button cancelBtn = new Button("CANCEL");
        cancelBtn.setStyle("-fx-background-color: #da3633; -fx-text-fill: white; -fx-font-weight: bold; " +
                "-fx-padding: 5 16; -fx-background-radius: 4; -fx-cursor: hand; -fx-font-family: 'Consolas'; -fx-font-size: 11px;");
        cancelBtn.setOnAction(e -> onCancelLast());

        HBox row = new HBox(10, buyBtn, sellBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                pLbl, priceField, qLbl, quantityField,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                submitBtn, cancelBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 12, 8, 12));
        row.setStyle("-fx-background-color: #111820; -fx-background-radius: 6; -fx-border-color: #1c2333; -fx-border-radius: 6;");
        return row;
    }

    private String inputStyle() {
        return "-fx-background-color: #0a0e14; -fx-text-fill: #c9d1d9; " +
               "-fx-border-color: #1c2333; -fx-border-radius: 3; -fx-background-radius: 3; " +
               "-fx-font-family: 'Consolas'; -fx-font-size: 12px; -fx-padding: 3 6;";
    }

    // ── Log Console ──────────────────────────────────────────────────────

    private VBox createLogConsole() {
        Label hdr = new Label("EXECUTION LOG");
        hdr.setStyle("-fx-text-fill: #484f58; -fx-font-size: 9px; -fx-font-weight: bold; -fx-font-family: 'Consolas';");

        logConsole = new TextArea();
        logConsole.setEditable(false);
        logConsole.setPrefRowCount(5);
        logConsole.setWrapText(true);
        logConsole.setStyle("-fx-control-inner-background: #0a0e14; -fx-text-fill: #c9d1d9; " +
                "-fx-font-family: 'Consolas'; -fx-font-size: 11px; " +
                "-fx-border-color: #1c2333; -fx-border-radius: 4; -fx-background-radius: 4;");

        VBox box = new VBox(2, hdr, logConsole);
        VBox.setVgrow(logConsole, Priority.ALWAYS);
        return box;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ANIMATION RENDER LOOP (60fps)
    // ══════════════════════════════════════════════════════════════════════

    private void startRenderLoop() {
        renderLoop = new AnimationTimer() {
            @Override
            public void handle(long now) {
                // 1. Drain pending trades from matching engine without lock contention
                processPendingTrades();

                // 2. Render price chart (allocation-free)
                renderPriceChart();

                // 3. Throttle depth refresh to avoid unnecessary redraws
                if (now - lastDepthRefresh > DEPTH_REFRESH_NS) {
                    renderDepthChart();
                    lastDepthRefresh = now;
                }
            }
        };
        renderLoop.start();
    }

    /**
     * Drains trade events queued by the matching engine on the FX Application Thread.
     * Keeps the engine hot path completely non-blocking.
     */
    private void processPendingTrades() {
        if (tradeQueue.isEmpty()) return;

        TradeEvent trade;
        int processed = 0;
        TradeEvent lastTrade = null;
        boolean isBench = benchmarkSimulator != null && benchmarkSimulator.isRunning();

        while ((trade = tradeQueue.poll()) != null && processed < 2000) {
            processed++;
            lastTrade = trade;
            double price = trade.priceTicks() / 100.0;
            addPricePoint(price);

            // Throttle UI tape additions during high-throughput benchmark
            if (!isBench || processed % 20 == 0 || tradeQueue.isEmpty()) {
                addTradeToTape(trade);
            }

            if (!isBench) {
                appendLog("TRADE", String.format("Fill #%d↔#%d $%s ×%d",
                        trade.aggressorOrderId(), trade.restingOrderId(),
                        formatTicks(trade.priceTicks()), trade.quantity()));
            }
        }

        if (lastTrade != null) {
            double price = lastTrade.priceTicks() / 100.0;
            previousPrice = lastTradePrice;
            lastTradePrice = price;

            // Auto-scale chart bounds smoothly
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (int i = 0; i < priceHistoryCount; i++) {
                double p = getPricePoint(i);
                if (p < min) min = p;
                if (p > max) max = p;
            }
            double margin = (max - min) * 0.1 + 0.01;
            priceHigh = max + margin;
            priceLow = min - margin;

            updateHeaderPriceDisplay();
        }
    }

    private void updateHeaderPriceDisplay() {
        boolean up = lastTradePrice >= previousPrice;
        lastPriceLabel.setText(String.format("$%.2f", lastTradePrice));
        lastPriceLabel.setStyle("-fx-text-fill: " + (up ? "#00d68f" : "#ff6b6b") +
                "; -fx-font-size: 28px; -fx-font-weight: bold; -fx-font-family: 'Consolas';");

        double change = lastTradePrice - 100.0;
        double pct = (change / 100.0) * 100.0;
        priceChangeLabel.setText(String.format("%s%.2f (%s%.2f%%)",
                change >= 0 ? "+" : "", change, pct >= 0 ? "+" : "", pct));
        priceChangeLabel.setStyle("-fx-text-fill: " + (change >= 0 ? "#00d68f" : "#ff6b6b") +
                "; -fx-font-size: 12px; -fx-font-family: 'Consolas';");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  EVENT HANDLERS
    // ══════════════════════════════════════════════════════════════════════

    private long lastOrderId = -1;

    private void onSubmitOrder() {
        try {
            long priceTicks = parsePriceTicks(priceField.getText());
            if (priceTicks <= 0) {
                throw new IllegalArgumentException("Price must be greater than zero");
            }

            String qtyStr = quantityField.getText().trim();
            if (qtyStr.isEmpty()) throw new IllegalArgumentException("Quantity cannot be empty");
            long qty = Long.parseLong(qtyStr);
            if (qty <= 0) throw new IllegalArgumentException("Quantity must be greater than zero");

            Side side = (Side) sideToggle.getSelectedToggle().getUserData();
            var order = engine.submitOrder(side, priceTicks, qty);
            lastOrderId = order.getOrderId();

            String status = order.getQuantity() == 0 ? "FILLED"
                    : order.getQuantity() < qty ? "PARTIAL resting=" + order.getQuantity()
                    : "RESTING";

            appendLog("ORDER", String.format("%s #%d $%s ×%d [%s]",
                    side, order.getOrderId(), formatTicks(priceTicks), qty, status));
        } catch (NumberFormatException ex) {
            appendLog("ERROR", "Invalid numeric format: " + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            appendLog("ERROR", ex.getMessage());
        }
    }

    private void onCancelLast() {
        if (lastOrderId < 0) { appendLog("WARN", "No order to cancel."); return; }
        boolean ok = engine.cancelOrder(lastOrderId);
        appendLog("CANCEL", ok ? "Cancelled #" + lastOrderId : "#" + lastOrderId + " not found");
    }

    private void onToggleBenchmark() {
        if (benchmarkSimulator != null && benchmarkSimulator.isRunning()) {
            benchmarkSimulator.stop();
            benchmarkSimulator = null;
            benchmarkBtn.setText("▶  START BENCHMARK");
            benchmarkBtn.setStyle("-fx-background-color: #1f6feb; -fx-text-fill: white; -fx-font-weight: bold; " +
                    "-fx-font-size: 11px; -fx-padding: 6 18; -fx-background-radius: 6; -fx-cursor: hand; -fx-font-family: 'Consolas';");
            benchStatusLabel.setText("STOPPED");
            benchStatusLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 10px; -fx-font-weight: bold; -fx-font-family: 'Consolas';");
            appendLog("BENCH", "Benchmark stopped.");
        } else {
            benchmarkSimulator = new BenchmarkSimulator(engine, this::onBenchmarkMetrics);
            benchmarkSimulator.start();
            benchmarkBtn.setText("⏹  STOP BENCHMARK");
            benchmarkBtn.setStyle("-fx-background-color: #da3633; -fx-text-fill: white; -fx-font-weight: bold; " +
                    "-fx-font-size: 11px; -fx-padding: 6 18; -fx-background-radius: 6; -fx-cursor: hand; -fx-font-family: 'Consolas';");
            benchStatusLabel.setText("● RUNNING");
            benchStatusLabel.setStyle("-fx-text-fill: #00d68f; -fx-font-size: 10px; -fx-font-weight: bold; -fx-font-family: 'Consolas';");
            appendLog("BENCH", "Benchmark started — 10,000 orders/sec with algorithmic cancellations...");
        }
    }

    private void onBenchmarkMetrics(long ops, double latUs, long total, long trades, double elapsedSec) {
        tpsLabel.setText(String.format("%,d", ops));
        latencyLabel.setText(String.format("%.2f", latUs));
        totalOrdersLabel.setText(String.format("%,d", total));
        totalTradesLabel.setText(String.format("%,d", trades));
        benchStatusLabel.setText(String.format("● RUNNING (%.1fs)", elapsedSec));
    }

    // ── Trade events ─────────────────────────────────────────────────────

    /** Hot-path trade callback from MatchingEngine: strictly O(1) lock-free enqueue. */
    private void onTrade(TradeEvent trade) {
        tradeQueue.offer(trade);
    }

    private void onBookChange() {
        // Book updates are naturally picked up by the AnimationTimer via getBidDepth/getAskDepth
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void appendLog(String tag, String msg) {
        String line = String.format("[%s] [%-6s] %s%n", TIME_FMT.format(Instant.now()), tag, msg);
        if (Platform.isFxApplicationThread()) {
            logConsole.appendText(line);
            if (logConsole.getText().length() > 20_000) {
                logConsole.setText(logConsole.getText().substring(10_000));
            }
        } else {
            Platform.runLater(() -> {
                logConsole.appendText(line);
                if (logConsole.getText().length() > 20_000) {
                    logConsole.setText(logConsole.getText().substring(10_000));
                }
            });
        }
    }

    /**
     * Robust price string parsing into tick integers ($100.50 -> 10050).
     * Handles ".50", "100.5", "100", "+100.05", etc.
     */
    static long parsePriceTicks(String text) {
        if (text == null) throw new NumberFormatException("Price cannot be null");
        text = text.trim();
        if (text.isEmpty()) throw new NumberFormatException("Price cannot be empty");

        boolean negative = false;
        if (text.startsWith("-")) {
            negative = true;
            text = text.substring(1).trim();
        } else if (text.startsWith("+")) {
            text = text.substring(1).trim();
        }

        long ticks;
        if (text.contains(".")) {
            String[] parts = text.split("\\.", -1);
            long whole = parts[0].isEmpty() ? 0L : Long.parseLong(parts[0]);
            String frac = parts.length > 1 ? parts[1] : "0";
            if (frac.isEmpty()) {
                frac = "00";
            } else if (frac.length() == 1) {
                frac = frac + "0";
            } else if (frac.length() > 2) {
                frac = frac.substring(0, 2);
            }
            ticks = whole * 100L + Long.parseLong(frac);
        } else {
            ticks = Long.parseLong(text) * 100L;
        }
        return negative ? -ticks : ticks;
    }

    static String formatTicks(long ticks) {
        if (ticks < 0) {
            long abs = Math.abs(ticks);
            return String.format("-%d.%02d", abs / 100, abs % 100);
        }
        return String.format("%d.%02d", ticks / 100, ticks % 100);
    }

    // ── Main ─────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        launch(args);
    }
}
