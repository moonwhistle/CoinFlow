import { LiveTicker } from './components/LiveTicker';
import { Header } from './components/Header';
import './App.css';

function App() {
  return (
    <div className="app-container">
      <Header />

      <main className="main-content">
        {/* Chart placeholder */}
        <div style={{ padding: '20px', color: 'var(--text-muted)' }}>
          Chart Area (Coming Soon)
        </div>
      </main>

      <aside className="right-panel">
        <LiveTicker />
      </aside>
    </div>
  );
}

export default App;
