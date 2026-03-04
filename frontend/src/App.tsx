import { LiveTicker } from './components/LiveTicker';
import { Header } from './components/Header';
import { TradingChart } from './components/Chart/TradingChart';
import './App.css';

import { WebSocketProvider } from './context/WebSocketContext';

function App() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const WS_URL = `${protocol}//${window.location.host}/ws/v1/coinflow`;

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
