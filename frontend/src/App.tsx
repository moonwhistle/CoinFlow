import { LiveTicker } from './components/LiveTicker';
import { Header } from './components/Header';
import { TradingChart } from './components/Chart/TradingChart';
import './App.css';

function App() {
  return (
    <div className="app-container">
      <Header />

      <main className="main-content">
        <TradingChart />
      </main>

      <aside className="right-panel">
        <LiveTicker />
      </aside>
    </div>
  );
}

export default App;
