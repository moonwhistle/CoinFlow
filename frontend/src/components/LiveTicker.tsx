import { useEffect, useState, useRef } from 'react';
import { useCoinflowWebSocket } from '../hooks/useCoinflowWebSocket';
import './LiveTicker.css';

const WS_URL = 'ws://localhost:8080/ws/v1/coinflow';
const SYMBOL = 'btcusdt';

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
        if (lastMessage?.price) {
            const currentPrice = parseFloat(lastMessage.price);
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

    const formatPrice = (priceStr: string | undefined) => {
        if (!priceStr) return '---';
        const price = parseFloat(priceStr);
        return new Intl.NumberFormat('en-US', {
            style: 'currency',
            currency: 'USD',
            minimumFractionDigits: 2,
            maximumFractionDigits: 2,
        }).format(price);
    };

    const formatQuantity = (qtyStr: string | undefined) => {
        if (!qtyStr) return '---';
        return parseFloat(qtyStr).toFixed(6);
    };

    const formatTime = (timeStr: string | undefined) => {
        if (!timeStr) return '---';
        return new Date(timeStr).toLocaleTimeString();
    };

    return (
        <div className="ticker-container">
            <div className="header">
                <div className="symbol">
                    <img
                        src="https://cryptologos.cc/logos/bitcoin-btc-logo.svg?v=040"
                        alt="BTC"
                        style={{ width: '24px', height: '24px' }}
                    />
                    BTC / USDT
                </div>
                <div className={`status-dot ${isConnected ? 'connected' : ''}`} title={isConnected ? 'Connected' : 'Disconnected'} />
            </div>

            <div className="price-container">
                <div className={`price ${priceColor}`}>
                    {formatPrice(lastMessage?.price)}
                </div>
            </div>

            <div className="details">
                <div className="detail-item">
                    <span className="detail-label">24h Change</span>
                    <span className="detail-value" style={{ color: 'var(--text-secondary)' }}>
                        {/* Placeholder as we don't have 24h stats yet */}
                        +0.00%
                    </span>
                </div>
                <div className="detail-item">
                    <span className="detail-label">Quantity</span>
                    <span className="detail-value">{formatQuantity(lastMessage?.quantity)}</span>
                </div>
                <div className="detail-item">
                    <span className="detail-label">Time</span>
                    <span className="detail-value">{formatTime(lastMessage?.eventTime)}</span>
                </div>
                <div className="detail-item">
                    <span className="detail-label">Status</span>
                    <span className="detail-value" style={{ color: isConnected ? 'var(--accent-green)' : 'var(--text-secondary)' }}>
                        {isConnected ? 'Market Open' : 'Connecting...'}
                    </span>
                </div>
            </div>

            {!lastMessage && isConnected && (
                <div className="footer">
                    <p className="loading-text">Waiting for tick data...</p>
                </div>
            )}
        </div>
    );
};
