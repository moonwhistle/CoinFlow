import { useEffect, useRef, useState, useCallback } from 'react';
import { createChart, ColorType, CandlestickSeries, HistogramSeries } from 'lightweight-charts';
import type { Time, LogicalRange, IChartApi, ISeriesApi } from 'lightweight-charts';
import { Settings, Camera, Maximize, BarChart2 } from 'lucide-react';
import { useCoinflowWebSocket } from '../../hooks/useCoinflowWebSocket';
import { CHART_COLORS, CHART_CONFIG } from '../../constants/chart';
import { aggregateTickToCandle, generateMockData } from '../../utils/chartHelpers';
import type { ChartCandle, VolumeBar } from '../../utils/chartHelpers';
import type { TickData } from '../../types/websocket';
import './TradingChart.css';

export const TradingChart = () => {
    const mainContainerRef = useRef<HTMLDivElement>(null);
    const volumeContainerRef = useRef<HTMLDivElement>(null);

    // Refs for chart APIs to access inside effects/callbacks without re-rendering
    // Using useRef instead of useState to prevent re-renders on high-frequency data updates (100+ ticks/sec)
    const mainChartRef = useRef<IChartApi | null>(null);
    const volumeChartRef = useRef<IChartApi | null>(null);
    const mainSeriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);
    const volumeSeriesRef = useRef<ISeriesApi<"Histogram"> | null>(null);

    const [activeTimeframe, setActiveTimeframe] = useState(CHART_CONFIG.DEFAULT_TIMEFRAME);

    // State to track the current accumulating candle
    const currentCandleRef = useRef<ChartCandle | null>(null);
    const currentVolumeRef = useRef<VolumeBar | null>(null);

    // --- Real-time Data Handling (Performance Optimized) ---
    // Use useCallback to keep the function reference stable across renders.
    // Logic extracted to aggregateTickToCandle for SRP and reusability.
    const handleTick = useCallback((tick: TickData) => {
        if (!mainSeriesRef.current || !volumeSeriesRef.current) return;

        const { candle, volume } = aggregateTickToCandle(
            tick,
            currentCandleRef.current,
            currentVolumeRef.current,
            CHART_COLORS.UP_TRANSPARENT,
            CHART_COLORS.DOWN_TRANSPARENT
        );

        if (candle && volume) {
            // Apply Update Direct to Chart (No React Render)
            mainSeriesRef.current.update(candle);
            volumeSeriesRef.current.update(volume);

            // Update Refs
            currentCandleRef.current = candle;
            currentVolumeRef.current = volume;
        }
    }, []);

    // WebSocket Hook with Callback
    // Pass handleTick to avoid state updates on every tick
    const { isConnected, subscribe } = useCoinflowWebSocket(
        'ws://localhost:8080/ws/connect',
        { onMessage: handleTick }
    );

    // --- Chart Initialization ---
    useEffect(() => {
        if (!mainContainerRef.current || !volumeContainerRef.current) return;

        // 1. Initialize Main Chart (Price)
        const mainChart = createChart(mainContainerRef.current, {
            layout: {
                background: { type: ColorType.Solid, color: CHART_COLORS.BACKGROUND },
                textColor: CHART_COLORS.TEXT,
            },
            grid: {
                vertLines: { color: CHART_COLORS.GRID },
                horzLines: { color: CHART_COLORS.GRID },
            },
            width: mainContainerRef.current.clientWidth,
            height: mainContainerRef.current.clientHeight,
            timeScale: {
                visible: false,
                minBarSpacing: CHART_CONFIG.MIN_BAR_SPACING,
            },
            rightPriceScale: {
                borderColor: CHART_COLORS.BORDER,
                minimumWidth: CHART_CONFIG.PRICE_SCALE_WIDTH,
            },
        });

        const mainSeries = mainChart.addSeries(CandlestickSeries, {
            upColor: CHART_COLORS.UP,
            downColor: CHART_COLORS.DOWN,
            borderVisible: false,
            wickUpColor: CHART_COLORS.UP,
            wickDownColor: CHART_COLORS.DOWN,
        });

        // 2. Initialize Volume Chart
        const volumeChart = createChart(volumeContainerRef.current, {
            layout: {
                background: { type: ColorType.Solid, color: CHART_COLORS.BACKGROUND },
                textColor: CHART_COLORS.TEXT,
            },
            grid: {
                vertLines: { color: CHART_COLORS.GRID },
                horzLines: { color: CHART_COLORS.GRID },
            },
            width: volumeContainerRef.current.clientWidth,
            height: volumeContainerRef.current.clientHeight,
            timeScale: {
                timeVisible: true,
                secondsVisible: false,
                borderColor: CHART_COLORS.BORDER,
                minBarSpacing: CHART_CONFIG.MIN_BAR_SPACING,
            },
            rightPriceScale: {
                borderColor: CHART_COLORS.BORDER,
                minimumWidth: CHART_CONFIG.PRICE_SCALE_WIDTH,
            },
            leftPriceScale: {
                visible: false,
            },
        });

        const volumeSeries = volumeChart.addSeries(HistogramSeries, {
            color: CHART_COLORS.UP,
            priceFormat: { type: 'volume' },
        });

        // 3. Generate Mock Data 
        // Note: Used for initial prototype. Will be replaced by Historical Data API.
        const { candleData, volumeData } = generateMockData(100);
        mainSeries.setData(candleData);
        volumeSeries.setData(volumeData);

        // Set Refs
        mainChartRef.current = mainChart;
        volumeChartRef.current = volumeChart;
        mainSeriesRef.current = mainSeries;
        volumeSeriesRef.current = volumeSeries;

        // Initialize current candle ref with the last mock candle to allow continuation
        if (candleData.length > 0 && volumeData.length > 0) {
            currentCandleRef.current = candleData[candleData.length - 1];
            currentVolumeRef.current = volumeData[volumeData.length - 1];
        }

        // 4. Sync
        const mainTimeScale = mainChart.timeScale();
        const volTimeScale = volumeChart.timeScale();

        const syncVolRange = (range: LogicalRange | null) => {
            if (range) volTimeScale.setVisibleLogicalRange(range);
        };
        const syncMainRange = (range: LogicalRange | null) => {
            if (range) mainTimeScale.setVisibleLogicalRange(range);
        };

        mainTimeScale.subscribeVisibleLogicalRangeChange(syncVolRange);
        volTimeScale.subscribeVisibleLogicalRangeChange(syncMainRange);

        // Crosshair Sync
        mainChart.subscribeCrosshairMove((param) => {
            if (param.time) {
                volumeChart.setCrosshairPosition(0, param.time, volumeSeries);
            } else {
                volumeChart.clearCrosshairPosition();
            }
        });

        volumeChart.subscribeCrosshairMove((param) => {
            if (param.time) {
                mainChart.setCrosshairPosition(0, param.time, mainSeries);
            } else {
                mainChart.clearCrosshairPosition();
            }
        });

        mainChart.timeScale().fitContent();

        // 5. Resize
        const handleResize = () => {
            // ... resize logic ...
            // Simplified for brevity, same logic as before
            if (mainChartRef.current && mainContainerRef.current) {
                mainChartRef.current.applyOptions({
                    width: mainContainerRef.current.clientWidth,
                    height: mainContainerRef.current.clientHeight
                });
            }
            if (volumeChartRef.current && volumeContainerRef.current) {
                volumeChartRef.current.applyOptions({
                    width: volumeContainerRef.current.clientWidth,
                    height: volumeContainerRef.current.clientHeight
                });
            }
        };
        const resizeObserver = new ResizeObserver(() => handleResize());
        resizeObserver.observe(mainContainerRef.current);

        return () => {
            resizeObserver.disconnect();
            mainTimeScale.unsubscribeVisibleLogicalRangeChange(syncVolRange);
            volTimeScale.unsubscribeVisibleLogicalRangeChange(syncMainRange);
            mainChart.remove();
            volumeChart.remove();
        };
    }, []);

    // --- WebSocket Subscription ---
    useEffect(() => {
        if (isConnected) {
            console.log("Subscribing to BTCUSDT...");
            subscribe('BTCUSDT');
        }
    }, [isConnected, subscribe]);

    return (
        <div className="chart-wrapper">
            <div className="chart-toolbar">
                <div className="time-frame-selector">
                    {/* Temporarily disabled real switching for now, just UI */}
                    {['1m', '15m', '1h', '4h', '1D', '1W'].map((tf) => (
                        <button
                            key={tf}
                            className={`tf-btn ${activeTimeframe === tf ? 'active' : ''}`}
                            onClick={() => setActiveTimeframe(tf)}
                        >
                            {tf}
                        </button>
                    ))}
                </div>
                <div className="chart-tools-right">
                    <span style={{ fontSize: 12, marginRight: 10, color: isConnected ? '#4caf50' : '#f44336' }}>
                        {isConnected ? '● Connected' : '○ Disconnected'}
                    </span>
                    <BarChart2 size={18} className="tool-icon" />
                    <Settings size={18} className="tool-icon" />
                    <Camera size={18} className="tool-icon" />
                    <Maximize size={18} className="tool-icon" />
                </div>
            </div>

            <div
                ref={mainContainerRef}
                className="chart-container"
                style={{ flex: 3, borderBottom: '1px solid var(--border-color)' }}
            />

            <div
                ref={volumeContainerRef}
                className="chart-volume-container"
                style={{ flex: 1, minHeight: 0 }}
            />
        </div>
    );
};
