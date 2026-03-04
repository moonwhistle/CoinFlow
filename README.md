# 🪙 CoinFlow

Real-time Cryptocurrency Trading Chart Service (Only Bitcoin)

## 💡 Why do i start this project?

My first project was a stock chart service built entirely using APIs for market data, including OHLC and tick data.

During this project, I wanted to build everything starting from raw tick data.

## ⚽ Goal

- Build a real-time cryptocurrency trading chart service using only **Tick** data.
- Latency Minimization
- Strong Consistency

## 🛠️ Tech Stack

### Backend
*   **Core**: Java 17, Spring Boot 3.2
*   **Messaging**: Redis (Stream, Pub/Sub)
*   **Database**: PostgreSQL (JPA/Hibernate)
*   **Build**: Gradle (Multi-module Architecture)

### Frontend
*   **Core**: React, TypeScript, Vite
*   **State**: Context API (WebSocket Management)
*   **Visualization**: Lightweight Charts (Financial Charting)

### AI
* **IDE & Tools**: Antigravity IDE, Gemini 3 Pro
* **UI Design**: Stitch AI
* **Workflow**: 
    * **Document-Driven Development (DDD)**: Wrote requirements first to guide AI correctly.
    * **Rule-Based Coding**: Used project rules (`.rule`) to keep code clean and consistent.
    * **Frontend (Fast)**: Used **Vibe Coding** to see results immediately.
    * **Backend**: Used AI to find bugs and test edge cases, and code review.



## 🏗️ System Architecture (Single Server)

NOT READY


## 📊 Data Flow
Designed **Unidirectional Data Flow** with a **Single Aggregator** to guarantee 100% data consistency and zero latency UX.

![Data Flow](</image/dataFlowVersion3.png>)

### Core Logic
To achieve both Extreme Real-time Responsiveness and Strong Consistency without Client-Side Complexity.

- **Collector**: Pushes raw tick data to the Message Queue (Redis Stream) as fast as possible.
- **Consumer (Single Aggregator)**: Consumes raw ticks and builds perfect OHLC (Kline) candles in-memory.
- **View**: The WebSocket Gateway simply broadcasts the current tick and current ohlc candle(250ms delay) directly to the Dashboard.

> This unified approach ensures strict data consistency between the server and the client without complex synchronization logic.

### Data Flow Evolution
- 👴 **[Legacy] [First Design: Dual-Path Architecture](https://sanghu-i.tistory.com/124)** 
   - Initially separated into a Speed Layer (Redis Stream) for zero-latency UX and an Accuracy Layer (Candle Closed Event) to correct client-side data.
- 👶 **[Current] [Shift to Single Aggregator](https://sanghu-i.tistory.com/126)**
   - Shifted to a Unidirectional flow to eliminate complex front-end calculations and guarantee 100% identical Server-Client states using in-memory aggregation.


## 🧑‍💻 Getting Started

### Backend
```bash
not ready
```

### Frontend
```bash
not ready
```

## 🛠️ Technical Decisions & Troubleshooting

### Volume Scaling Strategy 
To aggregate the volume without sacrificing precision or system latency, Use a **Long Scaling Strategy**.
- **Problem**: `double` (IEEE 754) causes critical floating-point inaccuracies in financial data. On the other hand, `BigDecimal` guarantees accuracy but creating 10,000+ new objects per second causes severe Garbage Collection (GC) overhead and Stop-The-World latency spikes.
- **Solution**: Scale incoming tick volumes by $10^8$ and accumulate them as primitive `long` types using `Math.addExact()`. This ensures **zero object creation** and maximum CPU efficiency. The accumulated long value is only converted back to `BigDecimal` exactly when the candle snapshot is pushed to the client.

For more details.. [click here](https://sanghu-i.tistory.com/125)

### Save scaled volume to DB (why not decimal?)
summary

 - **B-Tree Indexing efficiency**:

 - **Aggregation Performance**:

 - **Data Integrity**:


## Disclaimer
This project is a personal, educational project built for learning purposes only.
