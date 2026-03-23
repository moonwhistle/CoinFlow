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

![Architecture](/image/architect2.png)

(In the current deployment, ElastiCache and RDS run as Docker containers on the same EC2 to minimize cost...)

> **Why Single Server?** Multi-instance deployments with ALB are unnecessary at this scale(including cost problems). A single EC2 with Nginx achieves the same routing and SSL at zero cost — while remaining ready to scale out when needed.

## 📊 Data Flow
Designed **Unidirectional Data Flow** with a **Single Aggregator** to guarantee 100% data consistency and zero latency UX.

![Data Flow](</image/dataFlowVersion4.png>)

### Core Logic
To achieve both Extreme Real-time Responsiveness and Strong Consistency without Client-Side Complexity.

- **Collector**: Pushes raw tick data to the Message Queue (Redis Stream) as fast as possible.
- **Consumer (Single Aggregator)**: Consumes raw ticks and builds perfect OHLC  candle in-memory.
- **View**: The WebSocket Gateway simply broadcasts the current tick and current ohlc candle(250ms delay) directly to the Dashboard.
- **Replay (Spring Batch)**: Periodically(5min) syncs with Binance API to fix data gaps and guarantee 100% accuracy.

> This unified approach ensures strict data consistency between the server and the client without complex synchronization logic.

### Data Flow Evolution
- 👵 **[Legacy] [Dual-Path Architecture](https://sanghu-i.tistory.com/124)** 
   - Initially separated into a Speed Layer (Redis Stream) for zero-latency UX and an Accuracy Layer (Candle Closed Event) to correct client-side data.

- 👶 **[Current] [Single Aggregator](https://sanghu-i.tistory.com/126)**
   - Shifted to a Unidirectional flow to eliminate complex front-end calculations and guarantee 100% identical Server-Client states using in-memory aggregation.

## 🧑‍💻 Getting Started

### Backend
1. Build JARs: `./gradlew build -x test`
2. Run with Docker Compose: `docker compose -f infra/docker/docker-compose-prod.yml up -d`

### Frontend
1. Install dependencies: `npm install`
2. Run development server: `npm run dev`

## 🛠️ Technical Decisions & Troubleshooting

### Volume Scaling Strategy 
To aggregate the volume without sacrificing precision or system latency, Use a **Long Scaling Strategy**.
- **Problem**: `double` (IEEE 754) causes critical floating-point inaccuracies in financial data. On the other hand, `BigDecimal` guarantees accuracy but creating 10,000+ new objects per second causes GC overhead and Stop-The-World latency spikes.
- **Solution**: Scale incoming tick volumes by $10^8$ and accumulate them as primitive `long` types using `Math.addExact()`. This ensures **zero object creation** and maximum CPU efficiency. The accumulated long value is only converted back to `BigDecimal` exactly when the candle snapshot is pushed to the client.

For more details.. [click here](https://sanghu-i.tistory.com/125)

### Candle Data Storage Strategy(For Real-time)
To keep tickers fast, save candle data to a separate **Async Thread Pool**. 

Since data can be late or duplicated, use a **Unique Index** and **Optimistic Locking** to keep charts accurate. 
This ensures maximum speed without sacrificing data integrity on a single CPU resource.

For more details.. [click here](https://sanghu-i.tistory.com/127)

### Improve Chart API Latency with Caffeine Local Cache
Applied **Caffeine Cache** to eliminate many DB queries for historical chart data, reducing API latency.

- **Thundering Herd Prevention**: Used **Atomic Loading** (`cache.get(key, loader)`) to ensure that even with thousands of concurrent requests on a cache miss, only **exactly one** thread queries the DB while others wait for the result.

- **Cache Penetration Defense**: Implemented **Early Validation** to reject requests for invalid symbols at the API entry point.

For more details.. [click here](https://sanghu-i.tistory.com/128)

## Disclaimer
This project is a personal, educational project built for learning purposes only.
