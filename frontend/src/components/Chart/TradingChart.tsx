import { useEffect, useRef, useState, useCallback } from 'react';
import { createChart, ColorType, CandlestickSeries, HistogramSeries } from 'lightweight-charts';
import type { Time, LogicalRange, IChartApi, ISeriesApi } from 'lightweight-charts';
import { Settings, Camera, Maximize, BarChart2 } from 'lucide-react';
import { useCoinflowWebSocket } from '../../hooks/useCoinflowWebSocket';
import { CHART_COLORS, CHART_CONFIG } from '../../constants/chart';
import { forwardFillCandles } from '../../utils/chartHelpers';
import type { ChartCandle, VolumeBar } from '../../utils/chartHelpers';
import { getOhlcData } from '../../api/ohlcApi';
import type { OhlcInterval, OhlcCandleSnapshot } from '../../types/chart';
import { type WsMessage, isKlineEvent, isTickerEvent } from '../../types/websocket';
import './TradingChart.css';

export const TradingChart = () => {
    const mainContainerRef = useRef<HTMLDivElement>(null);
    const volumeContainerRef = useRef<HTMLDivElement>(null);

    // Refs for chart APIs to access inside effects/callbacks without re-rendering
    const mainChartRef = useRef<IChartApi | null>(null);
    const volumeChartRef = useRef<IChartApi | null>(null);
    const mainSeriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);
    const volumeSeriesRef = useRef<ISeriesApi<"Histogram"> | null>(null);
    const lastCandleTimeRef = useRef<number>(0);

    // Default to M1 as per req
    const [activeTimeframe, setActiveTimeframe] = useState<OhlcInterval>('M1');
    const [isLoading, setIsLoading] = useState(true);

    // --- Real-time Data Handling (Kline Stream — Binance Style) ---
    const handleWebSocketMessage = useCallback((msg: WsMessage) => {
        if (!mainSeriesRef.current || !volumeSeriesRef.current) return;

        if (isKlineEvent(msg)) {
            // Filter: only render the active timeframe
            if (msg.interval !== activeTimeframe) return;

            const candleTime = msg.startTime as number;
            const isHistorical = candleTime < lastCandleTimeRef.current;

            try {
                mainSeriesRef.current.update({
                    time: candleTime as Time,
                    open: msg.open,
                    high: msg.high,
                    low: msg.low,
                    close: msg.close,
                }, isHistorical);

                volumeSeriesRef.current.update({
                    time: candleTime as Time,
                    value: msg.volume,
                    color: msg.close >= msg.open
                        ? CHART_COLORS.UP_TRANSPARENT
                        : CHART_COLORS.DOWN_TRANSPARENT,
                }, isHistorical);

                if (!isHistorical) {
                    lastCandleTimeRef.current = Math.max(lastCandleTimeRef.current, candleTime);
                }
            } catch (err) {
                console.warn(`[TradingChart] Failed to update candle for time ${candleTime}:`, err);
            }
        }
        else if (isTickerEvent(msg)) {
            // TickerEvent (0ms real-time): Update ONLY the current latest candle's close price
            // Note: In lightweight-charts, updating a candle that already exists at `time` modifies it.
            // Since we don't have the exact `open/high/low/time` of the CURRENT candle inside the TickerEvent,
            // the safest robust approach for lightweight-charts is to wait for the next KlineEvent (250ms)
            // OR we can maintain the latest candle state in a ref and patch it.
            // For now, if we want to just flash the price, we can update the price scale or let KlineEvent handle the candle shape
            // and use TickerEvent for a separate Top-Bar UI component (Current Price Display).
            // Let's fire a custom DOM event so the outer dashboard can listen to the raw ticker if needed:
            const tickerEvent = new CustomEvent('coinflow-ticker', { detail: msg });
            window.dispatchEvent(tickerEvent);
        }
    }, [activeTimeframe]);

    // WebSocket Hook with Callback
    const { isConnected, subscribe } = useCoinflowWebSocket(handleWebSocketMessage);

    // --- Chart Initialization & Data Loading ---
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
            lastValueVisible: false,
            priceLineVisible: false,
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
            priceFormat: {
                type: 'custom',
                formatter: (price: number) => price.toFixed(4)
            },
        });

        // 3. Initial Data Load
        const loadInitialData = async () => {
            setIsLoading(true);
            try {
                // TODO: Symbol ID is hardcoded to 1 for now (assuming BTCUSDT = 1)
                const response = await getOhlcData(1, activeTimeframe, 120);

                const candles: ChartCandle[] = [];
                const volumes: VolumeBar[] = [];

                response.candles.forEach((snap: OhlcCandleSnapshot) => {
                    const time = snap.epochSeconds as Time;

                    candles.push({
                        time,
                        open: snap.openPrice,
                        high: snap.highPrice,
                        low: snap.lowPrice,
                        close: snap.closePrice
                    });

                    volumes.push({
                        time,
                        value: snap.volume,
                        color: snap.closePrice >= snap.openPrice ? CHART_COLORS.UP_TRANSPARENT : CHART_COLORS.DOWN_TRANSPARENT
                    });
                });

                // Sort by time
                candles.sort((a, b) => (a.time as number) - (b.time as number));
                volumes.sort((a, b) => (a.time as number) - (b.time as number));

                // Forward-fill gaps with semi-transparent ghost candles
                const { filledCandles, filledVolumes } = forwardFillCandles(candles, volumes, activeTimeframe);

                mainSeries.setData(filledCandles);
                volumeSeries.setData(filledVolumes);

                if (filledCandles.length > 0) {
                    const maxTime = Math.max(...filledCandles.map(c => c.time as number));
                    lastCandleTimeRef.current = maxTime;
                }
            } catch (err) {
                console.error("Failed to load chart data", err);
            } finally {
                setIsLoading(false);
            }
        };

        loadInitialData();

        // Set Refs
        mainChartRef.current = mainChart;
        volumeChartRef.current = volumeChart;
        mainSeriesRef.current = mainSeries;
        volumeSeriesRef.current = volumeSeries;

        // 4. Synchronization
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

        // 5. Resize Handling
        const handleResize = () => {
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
    }, [activeTimeframe]);

    // --- WebSocket Subscription ---
    useEffect(() => {
        if (isConnected) {
            console.log("Subscribing to btcusdt...");
            subscribe('btcusdt');
        }
    }, [isConnected, subscribe]);

    return (
        <div className="chart-wrapper">
            <div className="chart-toolbar">
                <div className="time-frame-selector">
                    {(['M1', 'M5', 'M30'] as OhlcInterval[]).map((tf) => (
                        <button
                            key={tf}
                            className={`tf-btn ${activeTimeframe === tf ? 'active' : ''}`}
                            onClick={() => setActiveTimeframe(tf)}
                            disabled={isLoading}
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

            <div style={{ flex: 1, minHeight: 0, position: 'relative' }}>
                <span className="volume-label">Vol (BTC)</span>
                <div
                    ref={volumeContainerRef}
                    className="chart-volume-container"
                    style={{ width: '100%', height: '100%' }}
                />
            </div>
        </div>
    );
};
