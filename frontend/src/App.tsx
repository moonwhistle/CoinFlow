import { LiveTicker } from './components/LiveTicker';
import { Header } from './components/Header';
import { TradingChart } from './components/Chart/TradingChart';
import './App.css';

import { WebSocketProvider } from './context/WebSocketContext';

function App() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const defaultWsUrl = `${protocol}//${window.location.host}/ws/v1/coinflow`;
  const WS_URL = import.meta.env.VITE_WS_BASE_URL || defaultWsUrl;

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
