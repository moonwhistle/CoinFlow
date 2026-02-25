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
Designed **dual-path flow**, which is consist of **Accuracy Layer** and **Speed Layer**.

![Data Flow](</image/dataFlowVersion2.png>)

### Why Dual-Path? 
To achieve both Real-time Responsiveness and Strong Consistency.

- Speed Layer (Fast-path): Uses Message Queue to deliver raw ticks and show ohlc candle immediately for zero-latency UX.
- Accuracy Layer (Slow-path): Uses Event Bus to broadcast confirmed candle data, correcting any client-side discrepancies.

>This hybrid approach ensures that users see price changes instantly while the system guarantees data integrity in the background.

For more details, see [Data Flow](<https://sanghu-i.tistory.com/124>)

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
