import { useEffect, useState, useRef, useCallback } from 'react';
import { useCoinflowWebSocket } from '../hooks/useCoinflowWebSocket';
import { Clock, Activity, BarChart2, Hash, Zap } from 'lucide-react';
import type { WsMessage, KlineEvent } from '../types/websocket';
import { isKlineEvent, isTickerEvent } from '../types/websocket';
import './LiveTicker.css';

// --- Constants (SRP/DRY/Magic Values) ---
const TICKER_CONSTANTS = {
    SYMBOL: 'btcusdt',
    LOGO_PATH: '/images/btc-logo.png',
    LOGO_ALT: 'BTC',
};

// --- Formatters ---
const currencyFormatter = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
});

const volumeFormatter = new Intl.NumberFormat('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
});

// Mock Data for 24h Stats (Fallback when backend API is unavailable)
const MOCK_STATS = {
    high: 89800.00,
    low: 88900.00,
    volume: 45231.05,
    changePercent: 2.45
};

export const LiveTicker = () => {
    const [lastMessage, setLastMessage] = useState<KlineEvent | null>(null);
    const [priceColor, setPriceColor] = useState<'up' | 'down' | 'neutral'>('neutral');
    const [imgError, setImgError] = useState(false);
    const prevPriceRef = useRef<number | null>(null);

    const handleMessage = useCallback((msg: WsMessage) => {
        // 1. Kline (Candle) Handling - M1 is used for highest freq ticker updates
        if (isKlineEvent(msg) && msg.interval === 'M1') {
            setLastMessage(msg);
        }
        // 2. Ticker Event (Price/Volume only) - Reserved for direct ticker streams
        else if (isTickerEvent(msg)) {
            // Logic to update UI from pure ticker events
            console.debug(`[LiveTicker] Received Ticker: ${msg.price}`);
        }
    }, []);

    const { isConnected, subscribe } = useCoinflowWebSocket(handleMessage);

    useEffect(() => {
        if (isConnected) {
            subscribe(TICKER_CONSTANTS.SYMBOL);
        }
    }, [isConnected, subscribe]);

    useEffect(() => {
        if (lastMessage) {
            const currentPrice = lastMessage.close;
            if (prevPriceRef.current !== null) {
                if (currentPrice > prevPriceRef.current) {
                    setPriceColor('up');
                } else if (currentPrice < prevPriceRef.current) {
                    setPriceColor('down');
                }
            }
            prevPriceRef.current = currentPrice;
        }
    }, [lastMessage]);

    // Derived Display Values
    const currentPrice = lastMessage?.close ?? null;
    const currentVolume = lastMessage?.volume ?? null;
    const currentTime = lastMessage ? lastMessage.startTime * 1000 : null;

    const displayPrice = currentPrice ? currencyFormatter.format(currentPrice) : '---';
    const displayQuantity = currentVolume ? currentVolume.toFixed(6) : '---';
    const displayTime = currentTime ? new Date(currentTime).toLocaleTimeString() : '--:--:--';

    return (
        <div className="ticker-container">
            {/* 1. Header Section with Robust Image Loading */}
            <div className="ticker-header">
                <div className="symbol-info">
                    {!imgError ? (
                        <img
                            src={TICKER_CONSTANTS.LOGO_PATH}
                            alt={TICKER_CONSTANTS.LOGO_ALT}
                            className="coin-icon"
                            onError={() => setImgError(true)}
                        />
                    ) : (
                        <div className="coin-icon-fallback">B</div>
                    )}
                    <div className="symbol-text">
                        <span className="symbol-name">BTC / USDT</span>
                        <span className="symbol-sub-name">Bitcoin Tether US</span>
                    </div>
                </div>
                <div className="live-indicator">
                    <div className={`pulse-dot ${isConnected ? 'active' : ''}`} />
                    <span className="live-text">Live</span>
                </div>
            </div>

            {/* 2. Big Price Display */}
            <div className="ticker-price-section">
                <div className={`price-display ${priceColor}`}>
                    {displayPrice}
                </div>
                <div className="price-change-pill up">
                    ↑ {MOCK_STATS.changePercent}% (24h)
                </div>
            </div>

            {/* 3. Stats Grid (24h Statistics) */}
            <div className="stats-grid">
                <StatRow
                    icon={<Activity size={14} />}
                    label="24h High"
                    value={volumeFormatter.format(MOCK_STATS.high)}
                />
                <StatRow
                    icon={<Activity size={14} style={{ transform: 'scaleY(-1)' }} />}
                    label="24h Low"
                    value={volumeFormatter.format(MOCK_STATS.low)}
                />
                <StatRow
                    icon={<BarChart2 size={14} />}
                    label="24h Volume"
                    value={`${volumeFormatter.format(MOCK_STATS.volume)} BTC`}
                />
                <StatRow
                    icon={<Hash size={14} />}
                    label="Quantity"
                    value={displayQuantity}
                />
            </div>

            {/* 4. Footer */}
            <div className="ticker-footer">
                <div className="footer-item">
                    <span className="stat-label">
                        <Clock size={14} /> Time
                    </span>
                    <span className="stat-value">{displayTime}</span>
                </div>
                <div className="footer-item">
                    <span className="stat-label">
                        <Zap size={14} /> Status
                    </span>
                    <span className={`status-badge ${isConnected ? 'connected' : ''}`}>
                        {isConnected ? '● Connected' : '○ Connecting...'}
                    </span>
                </div>
            </div>
        </div>
    );
};

// Helper Component for consistent rows
const StatRow = ({ icon, label, value }: { icon: React.ReactNode, label: string, value: string }) => (
    <div className="stat-row">
        <span className="stat-label">
            {icon} {label}
        </span>
        <span className="stat-value">{value}</span>
    </div>
);
