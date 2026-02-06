import { useEffect, useState, useRef } from 'react';
import { useCoinflowWebSocket } from '../hooks/useCoinflowWebSocket';
import { Clock, Activity, BarChart2, Hash, Zap } from 'lucide-react';
import { isTickDto, isCandleClosedEvent } from '../types/websocket';
import './LiveTicker.css';

const WS_URL = 'ws://localhost:8080/ws/v1/coinflow';
const SYMBOL = 'btcusdt';

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

// Mock Data for 24h Stats (Since backend doesn't support them yet)
const MOCK_STATS = {
    high: 89800.00,
    low: 88900.00,
    volume: 45231.05,
    changePercent: 2.45
};

export const LiveTicker = () => {
    const { isConnected, lastMessage, subscribe } = useCoinflowWebSocket(WS_URL);
    const [priceColor, setPriceColor] = useState<'up' | 'down' | 'neutral'>('neutral');
    const prevPriceRef = useRef<number | null>(null);

    useEffect(() => {
        if (isConnected) {
            subscribe(SYMBOL);
        }
    }, [isConnected, subscribe]);

    useEffect(() => {
        if (lastMessage) {
            let currentPrice: number | null = null;

            if (isTickDto(lastMessage)) {
                currentPrice = lastMessage.price;
            } else if (isCandleClosedEvent(lastMessage)) {
                currentPrice = lastMessage.close;
            }

            if (currentPrice !== null) {
                if (prevPriceRef.current !== null) {
                    if (currentPrice > prevPriceRef.current) {
                        setPriceColor('up');
                    } else if (currentPrice < prevPriceRef.current) {
                        setPriceColor('down');
                    }
                }
                prevPriceRef.current = currentPrice;
            }
        }
    }, [lastMessage]);

    // Helpers to extract data safely from Union Type
    const getPrice = () => {
        if (!lastMessage) return null;
        if (isTickDto(lastMessage)) return lastMessage.price;
        if (isCandleClosedEvent(lastMessage)) return lastMessage.close;
        return null;
    };

    const getVolume = () => {
        if (!lastMessage) return null;
        return lastMessage.volume; // Both types have 'volume' (number)
    };

    const getTime = () => {
        if (!lastMessage) return null;
        if (isTickDto(lastMessage)) return lastMessage.eventTime;
        if (isCandleClosedEvent(lastMessage)) return new Date(lastMessage.bucketTime).getTime();
        return null;
    };

    const currentPrice = getPrice();
    const currentVolume = getVolume();
    const currentTime = getTime();

    // Formatters using current or mock data
    const displayPrice = currentPrice
        ? currencyFormatter.format(currentPrice)
        : '---';

    const displayQuantity = currentVolume
        ? currentVolume.toFixed(6)
        : '---';

    const displayTime = currentTime
        ? new Date(currentTime).toLocaleTimeString()
        : '--:--:--';

    return (
        <div className="ticker-container">
            {/* 1. Header Section */}
            <div className="ticker-header">
                <div className="symbol-info">
                    <img
                        src="https://cryptologos.cc/logos/bitcoin-btc-logo.svg?v=040"
                        alt="BTC"
                        className="coin-icon"
                    />
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

            {/* 3. Stats Grid */}
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
