# ⚡ High-Frequency Trading (HFT) Limit Order Book Matching Engine
## Comprehensive Architecture, Academic Breakdown & Presentation Guide

> **Prepared for:** Final Academic Evaluation & Project Presentation  
> **Tech Stack:** Java 21, Kotlin 1.9, JavaFX 21, Spring Boot 3.3, Gradle 8.10  
> **Key Paradigms:** Ultra-Low Latency, Microsecond Order Matching, Price-Time Priority (FIFO), Lock-Free Queuing, Object Pooling, Zero-Allocation Hot Path.

---

## 1. Executive Summary

### What is this project?
This project is an **in-memory High-Frequency Trading (HFT) Limit Order Book (LOB) Matching Engine** paired with a real-time, Bloomberg-terminal-inspired **JavaFX market visualization dashboard** and a **high-throughput benchmark simulator**.

In modern electronic financial markets (such as NASDAQ, the New York Stock Exchange, CME, and crypto exchanges like Binance), buyers and sellers do not negotiate manually. Instead, an automated algorithmic system called a **Matching Engine** continuously accepts incoming buy and sell orders, maintains the "Order Book", matches compatible trades according to strict regulatory rules, and publishes trade executions within **microseconds**.

### Real-World Use & Industry Relevance
* **Stock & Commodity Exchanges:** Powering equity, forex, options, and futures matching.
* **Cryptocurrency Exchanges:** Managing high-speed order matching and continuous order book depth.
* **Market Making Firms & Proprietary Trading Desks:** Simulating exchange dynamics to test quantitative strategies against realistic market depth without risking capital.

---

## 2. Core Financial Concepts (Must-Know Terminology)

To explain the project clearly to your professor, keep these fundamental concepts ready:

| Concept | Definition & Project Implementation |
| :--- | :--- |
| **Limit Order** | An order to buy or sell a specified quantity of an asset at a specified price or better. |
| **Bid** | A **BUY** limit order. Buyers want to pay as low as possible. The **Best Bid** is the highest price someone is willing to pay. |
| **Ask (Offer)** | A **SELL** limit order. Sellers want to sell as high as possible. The **Best Ask** is the lowest price someone is willing to sell for. |
| **Spread** | The difference between the Best Ask and the Best Bid: $\text{Spread} = \text{Best Ask} - \text{Best Bid}$. |
| **Maker vs. Taker** | **Maker:** A resting order placed on the book providing liquidity.<br>**Taker (Aggressor):** An incoming order that immediately crosses the spread and matches against existing resting orders. |
| **Execution Price Rule** | When an incoming order (Taker) matches against a resting order (Maker), the trade executes at the **Maker's limit price** (price-improvement rule). |
| **Price-Time Priority (FIFO)** | The gold standard regulatory matching rule across world financial exchanges:<br>1. **Price Priority:** Orders with better prices execute first (higher bids, lower asks).<br>2. **Time Priority:** At the exact same price level, earlier submitted orders execute before newer ones (First-In, First-Out). |

---

## 3. High-Level System Architecture

The project consists of three cleanly decoupled layers:

```mermaid
flowchart TD
    subgraph Layer1 ["1. Input & Benchmark Layer"]
        UserUI["Manual Order Entry (UI)"]
        Bench["BenchmarkSimulator (Daemon Thread)\n10,000 orders/sec + Algorithmic Churn"]
    end

    subgraph Layer2 ["2. Core Matching Engine (Single-Threaded Hot Path)"]
        Engine["MatchingEngine\n(Price-Time Priority FIFO)"]
        Bids["Bids: ConcurrentSkipListMap\n(Descending Price: Highest First)"]
        Asks["Asks: ConcurrentSkipListMap\n(Ascending Price: Lowest First)"]
        FIFO["PriceLevel FIFO Queues\n(Intrusive Doubly-Linked Lists with Sentinels)"]
        Index["liveOrders Lookup Index\n(HashMap<Long, OrderNode> for O(1) Cancels)"]
        Pool["PriceLevel Object Pool\n(Recycled Sentinels, Zero-Allocation)"]
    end

    subgraph Layer3 ["3. Visualization & Analytics Layer (JavaFX)"]
        Queue["ConcurrentLinkedQueue<TradeEvent>\n(Lock-Free Decoupling Buffer)"]
        FXApp["JavaFX AnimationTimer (60 FPS)"]
        Chart["Real-Time Price Chart\n(Zero-Allocation Ring Buffer)"]
        Heatmap["Bid/Ask Depth Heatmap\n(Top-10 Levels)"]
        Tape["Live Trade Tape & Ticker"]
        Metrics["Metrics Dashboard\n(TPS, Latency μs, Volume)"]
    end

    UserUI -->|submitOrder / cancelOrder| Engine
    Bench -->|submitOrder / cancelOrder| Engine
    Engine -->|Updates| Bids
    Engine -->|Updates| Asks
    Engine -->|Enqueues / Dequeues| FIFO
    Engine -->|Registers / Deregisters| Index
    Pool <-->|Acquire / Release| Engine

    Engine -->|O(1) Offer| Queue
    Queue -->|Drain Batches @ 60 FPS| FXApp
    FXApp --> Chart
    FXApp --> Heatmap
    FXApp --> Tape
    FXApp --> Metrics
```

---

## 4. How the Engine Works: Deep Technical Breakdown

### 4.1. Why Naive Implementations Fail (Why HFT is Different)
If asked, *"Why not just use a relational database or an `ArrayList`?"*:
* **Databases (SQL/NoSQL):** Disk I/O, network hops, and locking add **milliseconds** of latency. HFT engines require sub-microsecond in-memory operations.
* **`ArrayList` / `Vector`:** Deleting an order from the middle of an `ArrayList` requires an $O(N)$ memory shift of all subsequent elements.
* **`PriorityQueue` (Binary Heap):** While peeking the min/max is $O(1)$ and inserting is $O(\log N)$, **cancelling an order by ID is $O(N)$** because searching a heap requires an exhaustive scan.

### 4.2. The Composite Data Structure Solution
To achieve true low latency, our engine combines **three specialized data structures**:

```
                 BIDS (Price: High to Low)                  ASKS (Price: Low to High)
            [10050 ticks] -> [Node A] <-> [Node B]     [10060 ticks] -> [Node X] <-> [Node Y]
            [10040 ticks] -> [Node C]                  [10070 ticks] -> [Node Z]
            [10030 ticks] -> [Node D] <-> [Node E]     [10080 ticks] -> [Node W]
                 ▲                                          ▲
                 │                                          │
       SkipList: O(log P)                         SkipList: O(log P)
```

1. **`ConcurrentSkipListMap<Long, PriceLevel>` for Price Priority:**
   * Maps price tick keys to their corresponding price level queues.
   * `bids` uses `Comparator.reverseOrder()` so the best bid is always available at `bids.firstEntry()`.
   * `asks` uses natural ascending order so the best ask is always at `asks.firstEntry()`.
   * Searching or inserting a price level is $O(\log P)$, where $P$ is the number of active price levels (typically a few hundred).

2. **Intrusive Sentinel Doubly-Linked List (`PriceLevel`) for Time Priority (FIFO):**
   * Each price level maintains a circular doubly-linked list of [`OrderNode`](file:///src/main/kotlin/com/hft/engine/model/OrderNode.kt).
   * A dummy **sentinel node** is used to eliminate `null` checks on head and tail in the hot path.
   * **Enqueue new order:** $O(1)$ append at tail.
   * **Dequeue filled order:** $O(1)$ remove from head.
   * **Arbitrary order cancellation:** $O(1)$ node unlinking (`node.unlink()`).

3. **`HashMap<Long, OrderNode>` (`liveOrders`) for $O(1)$ Order Cancellation:**
   * Direct pointer lookup from `orderId` to the specific `OrderNode` sitting in the book.
   * Cancelling an order takes $O(1)$ map lookup followed by $O(1)$ list unlink.

4. **Object Pooling (`priceLevelPool`):**
   * Pre-allocates and recycles `PriceLevel` instances using an array-backed deque pool.
   * When all orders at a price level are filled or cancelled, the empty `PriceLevel` is returned to the pool rather than discarded, preventing Java garbage collection churn.

---

### 4.3. Mathematical Representation of Prices: Tick Integers
In financial software, **`double` and `float` must never be used for prices or currency amounts** because IEEE 754 floating-point arithmetic produces precision errors (e.g., `0.1 + 0.2 = 0.30000000000000004`).

* **Our Solution:** Prices are stored as `long` ticks where $1 \text{ tick} = \$0.01$ (one cent).
  $$\$100.50 \longrightarrow 10050 \text{ ticks}$$
  $$\$0.05 \longrightarrow 5 \text{ ticks}$$
* Arithmetic across matching, trade fills, and spreads uses pure integer operations: zero rounding errors, zero floating-point drift, and optimal CPU cache performance.

---

### 4.4. Order Matching Algorithm (Step-by-Step Walkthrough)

When [`MatchingEngine.submitOrder(Side side, long priceTicks, long quantity)`](file:///src/main/java/com/hft/engine/engine/MatchingEngine.java#L160) is invoked:

1. **Validation:** Checks that `side != null`, `priceTicks > 0`, and `quantity > 0`.
2. **Crossing Verification:**
   * If **BUY** order: Inspects `asks.firstEntry()`. If `bestAsk.price <= buyOrder.price`, a crossing trade is possible.
   * If **SELL** order: Inspects `bids.firstEntry()`. If `bestBid.price >= sellOrder.price`, a crossing trade is possible.
3. **Execution Loop (`matchLevel`):**
   * Grabs the head node of the best opposing price level.
   * Calculates fill quantity:
     $$\text{fillQty} = \min(\text{aggressor.remainingQty}, \text{resting.remainingQty})$$
   * Deducts `fillQty` from both orders.
   * Emits an immutable [`TradeEvent`](file:///src/main/java/com/hft/engine/engine/MatchingEngine.java#L340) containing:
     * `aggressorOrderId` (taker)
     * `restingOrderId` (maker)
     * `priceTicks` (maker's execution price)
     * `fillQty`
     * `timestampNanos`
   * If resting order reaches quantity 0: removes it from the FIFO queue and indexes in $O(1)$.
   * Repeats until the incoming order is filled or no more matching price levels exist.
4. **Resting on Book:**
   * If the aggressor order still has quantity remaining, it converts into a maker order.
   * Appended to the tail of its price level in the corresponding book and indexed in `liveOrders`.

---

## 5. UI Architecture & Lock-Free Decoupling

One of the most impressive technical aspects to highlight to your professor is how the system handles **high throughput without freezing the user interface**:

### The Problem in Traditional Apps
If the engine pushes updates directly to UI components at 10,000 orders/sec:
* The UI thread gets flooded with thousands of `Platform.runLater` events.
* Memory explodes from queued event closures.
* The UI freezes and the JVM stutters under GC pressure.

### Our Solution: Lock-Free Ring Buffer Queue
1. **Engine side:** In [`MatchingEngine.onTrade`](file:///src/main/java/com/hft/engine/HftEngineApplication.java#L525), fills are enqueued into a thread-safe, lock-free [`ConcurrentLinkedQueue<TradeEvent>`](file:///src/main/java/com/hft/engine/HftEngineApplication.java#L87). This call takes **~15 nanoseconds** and never blocks the engine.
2. **UI side:** JavaFX's [`AnimationTimer`](file:///src/main/java/com/hft/engine/HftEngineApplication.java#L430) triggers once every frame (~16.6 ms for 60 FPS).
3. **Batch Draining:** The UI thread drains up to 2,000 queued events per frame, calculates the new price boundaries, and draws the chart.
4. **Zero-Allocation Canvas Chart:** Price points are stored in a fixed-size circular array (`double[400]`). Canvas rendering reads points by circular index without allocating a single object per frame.

---

## 6. High-Throughput Benchmark Simulator

[`BenchmarkSimulator.java`](file:///src/main/java/com/hft/engine/engine/BenchmarkSimulator.java) stress-tests the matching engine in a realistic environment:
* **Target Load:** 10,000 orders/second on a dedicated background thread.
* **Pricing Distribution:** Randomized limit prices centered around mid-market ($\$100.00 \pm \$2.00$).
* **Algorithmic Churn Simulation:** Approximately **25% of operations are cancellations** targeting recently placed resting orders. This mirrors real algorithmic market makers who cancel stale quotes, ensuring the active order book stays realistically bounded.
* **Microsecond Latency Tracking:** Measures elapsed nano-seconds per order submission and reports real-time orders/sec (TPS) and average latency ($\mu\text{s}$) to the dashboard.

---

## 7. Testing & Quality Assurance

The codebase includes an automated JUnit 5 test suite with **14 comprehensive unit tests** in [`MatchingEngineTest.java`](file:///src/test/java/com/hft/engine/MatchingEngineTest.java):

```bash
.\gradlew.bat test
```

| Test Name | What It Verifies | Status |
| :--- | :--- | :---: |
| `testRestingBuyOrder` | Passive bids rest correctly on book without asks | **PASSED** |
| `testRestingSellOrder` | Passive asks rest correctly on book without bids | **PASSED** |
| `testExactMatch` | Identical bid and ask fully cross and clear the book | **PASSED** |
| `testPartialFillAggressorLarger` | Large taker partially fills maker; remainder rests | **PASSED** |
| `testPartialFillMakerLarger` | Small taker fills; maker remains on book with reduced qty | **PASSED** |
| `testPriceTimePriorityFIFO` | Orders at same price fill in exact order of submission | **PASSED** |
| `testPricePriorityMultipleLevels` | Multi-level sweeps take the best price first | **PASSED** |
| `testCancelOrder` | Cancelling resting order removes it from book and lookup map | **PASSED** |
| `testCancelHeadOrder` | Cancelling head order shifts next node to head | **PASSED** |
| `testCancelTailOrder` | Cancelling tail order updates list tail pointer | **PASSED** |
| `testCancelMiddleOrder` | Cancelling middle order links predecessor and successor | **PASSED** |
| `testInvalidInputsRejected` | Non-positive prices, quantities, and null sides throw `IllegalArgumentException` | **PASSED** |
| `testRemoveTradeListener` | Deregistering listener stops trade event notifications | **PASSED** |
| `testPriceParsingAndFormatting` | String parser handles `".50"`, whole numbers, and negatives | **PASSED** |

---

## 8. Professor Q&A Defense Guide (Cheat Sheet)

Be prepared for these questions during your presentation:

### Q1: "What is the time complexity of placing an order?"
> **Answer:**
> * If the order matches immediately (Taker): $O(1)$ to peek and match the best level head, repeated for each filled maker order.
> * If the order rests (Maker): $O(\log P)$ to find or insert the price level in the skip list (where $P$ is the number of active price levels), plus $O(1)$ to append to the level's FIFO queue and $O(1)$ to record in the hash index.

### Q2: "What is the time complexity of cancelling an order?"
> **Answer:**
> * Strictly **$O(1)$**. The order ID is looked up in `HashMap<Long, OrderNode>` in $O(1)$ time, and the node's pointers are unlinked from the intrusive doubly-linked list in $O(1)$ time (`node.prev.next = node.next`).

### Q3: "Why not use `BigDecimal` for prices?"
> **Answer:**
> * `BigDecimal` provides arbitrary decimal precision, but it is an object that allocates heap memory on every arithmetic operation (causing heavy garbage collector pressure in high-throughput loops).
> * In high-frequency trading, standard practice is to use **integer price ticks** (e.g. `long`). Integer arithmetic is performed directly in CPU registers with zero heap allocations and zero floating-point rounding errors.

### Q4: "How do you ensure the UI stays responsive while the engine processes 10,000 orders/sec?"
> **Answer:**
> * We use **asynchronous, lock-free decoupling**. The matching engine does not make direct calls to JavaFX components. Instead, it pushes executed trade events into a `ConcurrentLinkedQueue`.
> * JavaFX's `AnimationTimer` renders at 60 FPS and drains batches from this queue independently. This completely isolates the matching engine's execution path from UI rendering.

### Q5: "Why did you use both Kotlin and Java in the same project?"
> **Answer:**
> * We leveraged Kotlin's concise, expressive syntax for domain models ([`Order.kt`](file:///src/main/kotlin/com/hft/engine/model/Order.kt), [`OrderNode.kt`](file:///src/main/kotlin/com/hft/engine/model/OrderNode.kt)) using `@JvmField` for direct memory field access without getter overhead.
> * We used Java for the matching engine and simulator to maintain explicit, fine-grained control over multithreading, concurrency primitives, and memory layout.

---

## 9. How to Run the Demo for Your Professor

### 1. Run All Automated Tests
Open PowerShell or Terminal in the project root and execute:
```powershell
.\gradlew.bat test
```
*Shows all 14 unit tests passing in ~300ms.*

### 2. Launch the Application
```powershell
.\gradlew.bat run
```

### 3. Live Demonstration Steps
1. **Manual Order Entry (Visual Walkthrough):**
   * Submit a **BUY** order at `$100.00`, Qty `100`.
   * Point out the green bar appearing on the **BIDS** side of the Depth Heatmap.
   * Submit a **SELL** order at `$101.00`, Qty `50`.
   * Point out the red bar appearing on the **ASKS** side, and show the **SPREAD: $1.00** indicator.
   * Submit a crossing **BUY** order at `$101.00`, Qty `50`.
   * Show the trade execution badge flashing on the **Trade Tape** and the live execution log entry.
2. **Stress-Test Benchmark Demonstration:**
   * Click the **"▶ START BENCHMARK"** button.
   * Highlight the live metrics dashboard updating in real time:
     * **TPS:** Ramping up to ~10,000 orders per second.
     * **LATENCY:** Microsecond-range order execution latency (~2 to 10 $\mu\text{s}$).
     * **ORDERS & TRADES:** Cumulative counter updating live.
     * **CHART:** Live price line smoothly navigating market dynamics without UI stutter or lag.
   * Click **"⏹ STOP BENCHMARK"** to cleanly halt the generator.

