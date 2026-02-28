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
- **View (Stateless)**: The WebSocket Gateway simply broadcasts the completely finished candles directly to the Dashboard. No client-side math required.

> This unified approach ensures strict data consistency between the server and the client without complex synchronization logic.

### Data Flow Evolution
- 👴 **[Legacy] First Design: Dual-Path Architecture** 👉 [Read Article](https://sanghu-i.tistory.com/124)
   - Initially separated into a Speed Layer (Redis Stream) for zero-latency UX and an Accuracy Layer (Redis Pub/Sub) to correct client-side data.
- 👶 **[Current] Shift to Single Aggregator** 👉 [Read Article](https://sanghu-i.tistory.com/126)
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
- not ready

## Disclaimer
This project is a personal, educational project built for learning purposes only.
