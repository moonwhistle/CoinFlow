import { useCallback, useEffect, useRef, useState } from 'react';
import { Activity, ArrowDown, ArrowUp, BarChart2, Clock, Hash, Zap } from 'lucide-react';
import { getMarketStats24h } from '../api/marketApi';
import { useCoinflowWebSocket } from '../hooks/useCoinflowWebSocket';
import type { MarketStats24h } from '../types/market';
import type { KlineEvent, TickerEvent, WsMessage } from '../types/websocket';
import { isKlineEvent, isTickerEvent } from '../types/websocket';
import './LiveTicker.css';

const TICKER_CONSTANTS = {
    SYMBOL_ID: 1,
    SYMBOL: 'btcusdt',
    LOGO_PATH: '/images/btc-logo.png',
    LOGO_ALT: 'BTC',
    STATS_REFRESH_INTERVAL_MS: 30_000,
};

const currencyFormatter = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
});

const numberFormatter = new Intl.NumberFormat('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
});

type PriceDirection = 'up' | 'down' | 'neutral';

export const LiveTicker = () => {
    const [lastKline, setLastKline] = useState<KlineEvent | null>(null);
    const [lastTicker, setLastTicker] = useState<TickerEvent | null>(null);
    const [marketStats, setMarketStats] = useState<MarketStats24h | null>(null);
    const [priceDirection, setPriceDirection] = useState<PriceDirection>('neutral');
    const [imgError, setImgError] = useState(false);
    const prevPriceRef = useRef<number | null>(null);

    const updatePriceDirection = useCallback((currentPrice: number) => {
        const previousPrice = prevPriceRef.current;

        if (previousPrice !== null) {
            if (currentPrice > previousPrice) {
                setPriceDirection('up');
            } else if (currentPrice < previousPrice) {
                setPriceDirection('down');
            }
        }

        prevPriceRef.current = currentPrice;
    }, []);

    const handleMessage = useCallback((msg: WsMessage) => {
        if (msg.symbol !== TICKER_CONSTANTS.SYMBOL) {
            return;
        }

        if (isTickerEvent(msg)) {
            setLastTicker(msg);
            updatePriceDirection(msg.price);
            return;
        }

        if (isKlineEvent(msg) && msg.interval === 'M1') {
            setLastKline(msg);
            updatePriceDirection(msg.close);
        }
    }, [updatePriceDirection]);

    const { isConnected, subscribe } = useCoinflowWebSocket(handleMessage);

    useEffect(() => {
        if (isConnected) {
            subscribe(TICKER_CONSTANTS.SYMBOL);
        }
    }, [isConnected, subscribe]);

    useEffect(() => {
        let disposed = false;

        const refreshStats = async () => {
            try {
                const stats = await getMarketStats24h(TICKER_CONSTANTS.SYMBOL_ID);
                if (!disposed) {
                    setMarketStats(stats);
                }
            } catch (error) {
                console.error('[LiveTicker] Failed to load 24h market stats:', error);
            }
        };

        refreshStats();
        const intervalId = window.setInterval(
            refreshStats,
            TICKER_CONSTANTS.STATS_REFRESH_INTERVAL_MS,
        );

        return () => {
            disposed = true;
            window.clearInterval(intervalId);
        };
    }, []);

    const statsBucketStart = marketStats === null
        ? null
        : Math.floor(marketStats.asOfEpochMillis / 60_000) * 60;
    const activeKline = lastKline !== null
        && (statsBucketStart === null || lastKline.startTime >= statsBucketStart)
        ? lastKline
        : null;
    const hasNewerTicker = lastTicker !== null
        && (marketStats === null || lastTicker.eventTime > marketStats.asOfEpochMillis);
    const currentPrice = hasNewerTicker
        ? lastTicker.price
        : marketStats?.currentPrice ?? activeKline?.close ?? lastTicker?.price ?? null;
    const currentQuantity = lastTicker?.volume ?? null;
    const currentTime = hasNewerTicker
        ? lastTicker.eventTime
        : marketStats?.asOfEpochMillis ?? lastTicker?.eventTime ?? null;

    const high24h = marketStats === null
        ? null
        : Math.max(
            marketStats.highPrice,
            activeKline?.high ?? marketStats.highPrice,
            currentPrice ?? marketStats.highPrice,
        );
    const low24h = marketStats === null
        ? null
        : Math.min(
            marketStats.lowPrice,
            activeKline?.low ?? marketStats.lowPrice,
            currentPrice ?? marketStats.lowPrice,
        );
    const volume24h = calculateLiveVolume(marketStats, activeKline);
    const changePercent24h = marketStats === null
        ? null
        : currentPrice !== null && marketStats.openPrice !== 0
            ? ((currentPrice - marketStats.openPrice) / marketStats.openPrice) * 100
            : marketStats.changePercent;
    const changeDirection: PriceDirection = changePercent24h === null || changePercent24h === 0
        ? 'neutral'
        : changePercent24h > 0 ? 'up' : 'down';

    const displayPrice = currentPrice === null ? '---' : currencyFormatter.format(currentPrice);
    const displayQuantity = currentQuantity === null ? '---' : currentQuantity.toFixed(8);
    const displayTime = currentTime === null
        ? '--:--:--'
        : new Date(currentTime).toLocaleTimeString();

    return (
        <div className="ticker-container">
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

            <div className="ticker-price-section">
                <div className={`price-display ${priceDirection}`}>
                    {displayPrice}
                </div>
                <div className={`price-change-pill ${changeDirection}`}>
                    {changeDirection === 'up' && <ArrowUp size={14} />}
                    {changeDirection === 'down' && <ArrowDown size={14} />}
                    {changePercent24h === null ? '---' : `${Math.abs(changePercent24h).toFixed(2)}% (24h)`}
                </div>
            </div>

            <div className="stats-grid">
                <StatRow
                    icon={<Activity size={14} />}
                    label="24h High"
                    value={high24h === null ? '---' : numberFormatter.format(high24h)}
                />
                <StatRow
                    icon={<Activity size={14} style={{ transform: 'scaleY(-1)' }} />}
                    label="24h Low"
                    value={low24h === null ? '---' : numberFormatter.format(low24h)}
                />
                <StatRow
                    icon={<BarChart2 size={14} />}
                    label="24h Volume"
                    value={volume24h === null ? '---' : `${numberFormatter.format(volume24h)} BTC`}
                />
                <StatRow
                    icon={<Hash size={14} />}
                    label="Quantity"
                    value={displayQuantity}
                />
            </div>

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
                        {isConnected ? 'Connected' : 'Connecting...'}
                    </span>
                </div>
            </div>
        </div>
    );
};

const StatRow = ({ icon, label, value }: { icon: React.ReactNode, label: string, value: string }) => (
    <div className="stat-row">
        <span className="stat-label">
            {icon} {label}
        </span>
        <span className="stat-value">{value}</span>
    </div>
);

const calculateLiveVolume = (
    stats: MarketStats24h | null,
    liveKline: KlineEvent | null,
): number | null => {
    if (stats === null) {
        return null;
    }
    if (liveKline === null) {
        return stats.volume;
    }

    const statsBucket = stats.currentCandleStartEpochSeconds;
    if (statsBucket === liveKline.startTime) {
        return stats.volume
            - stats.currentCandleVolume
            + Math.max(stats.currentCandleVolume, liveKline.volume);
    }
    if (statsBucket === null || liveKline.startTime > statsBucket) {
        return stats.volume + liveKline.volume;
    }

    return stats.volume;
};
