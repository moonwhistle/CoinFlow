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



## 🏗️ System Architecture (Single Server)

NOT READY


## 📊 Data Flow
Designed **dual-path flow**, which is consist of **Accuracy Layer** and **Speed Layer**.


![Data Flow](</image/dataFlowVersion1.png>)

### Why divide these two paths? : For Consistency and Speed
- In Speed-path, use **Message Queue** to send real-time data.

- In Slow-path, use **Event Bus** to provide accuracy data.

This design enables low-latency tick processing and guarantees backend–frontend consistency.

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
