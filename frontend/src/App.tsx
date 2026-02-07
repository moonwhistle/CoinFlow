import { LiveTicker } from './components/LiveTicker';
import { Header } from './components/Header';
import { TradingChart } from './components/Chart/TradingChart';
import './App.css';

import { WebSocketProvider } from './context/WebSocketContext';

function App() {
  const WS_URL = 'ws://localhost:8080/ws/coinflow';

  return (
    <WebSocketProvider url={WS_URL}>
      <div className="app-container">
        <Header />

        <main className="main-content">
          <TradingChart />
        </main>

        <aside className="right-panel">
          <LiveTicker />
        </aside>
      </div>
    </WebSocketProvider>
  );
}

export default App;
