import { useEffect, useRef, useState, useCallback } from 'react';
import { createChart, ColorType, CandlestickSeries, HistogramSeries } from 'lightweight-charts';
import type { Time, LogicalRange, IChartApi, ISeriesApi } from 'lightweight-charts';
import { Settings, Camera, Maximize, BarChart2 } from 'lucide-react';
import { useCoinflowWebSocket } from '../../hooks/useCoinflowWebSocket';
import { CHART_COLORS, CHART_CONFIG } from '../../constants/chart';
import { forwardFillCandles, uniqueSortData } from '../../utils/chartHelpers';
import type { ChartCandle, VolumeBar } from '../../utils/chartHelpers';
import { getOhlcData } from '../../api/ohlcApi';
import type { OhlcInterval, OhlcCandleSnapshot } from '../../types/chart';
import { type WsMessage, isKlineEvent, isTickerEvent } from '../../types/websocket';
import './TradingChart.css';

// --- Constants for Maintenance (SRP/DRY/Magic Values) ---
const CHART_CONSTANTS = {
    DEFAULT_SYMBOL_ID: 1,
    DEFAULT_SYMBOL_NAME: 'btcusdt',
    PAGE_SIZE: 120,
    SCROLL_THRESHOLD: 10,
    INITIAL_FILL_DELAY_MS: 500,
};

export const TradingChart = () => {
    const mainContainerRef = useRef<HTMLDivElement>(null);
    const volumeContainerRef = useRef<HTMLDivElement>(null);

    // Refs for data management and flow control
    const mainChartRef = useRef<IChartApi | null>(null);
    const volumeChartRef = useRef<IChartApi | null>(null);
    const mainSeriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);
    const volumeSeriesRef = useRef<ISeriesApi<"Histogram"> | null>(null);
    const lastCandleTimeRef = useRef<number>(0);

    // Pagination & Infinite Scroll Control
    const isFetchingRef = useRef<boolean>(false);
    const hasMoreRef = useRef<boolean>(true);
    const rawDataRef = useRef<{ candles: ChartCandle[], volumes: VolumeBar[] }>({ candles: [], volumes: [] });

    // State
    const [activeTimeframe, setActiveTimeframe] = useState<OhlcInterval>('M1');
    const [isLoading, setIsLoading] = useState(true);

    // --- Data Loading & Merging ---
    const loadChartData = useCallback(async (to?: string) => {
        if (isFetchingRef.current || (!hasMoreRef.current && to)) return;

        isFetchingRef.current = true;
        try {
            const response = await getOhlcData(CHART_CONSTANTS.DEFAULT_SYMBOL_ID, activeTimeframe, CHART_CONSTANTS.PAGE_SIZE, to);

            if (response.candles.length === 0) {
                hasMoreRef.current = false;
                return;
            }

            const newCandles: ChartCandle[] = [];
            const newVolumes: VolumeBar[] = [];

            response.candles.forEach((snap: OhlcCandleSnapshot) => {
                const time = snap.epochSeconds as Time;
                newCandles.push({
                    time,
                    open: snap.openPrice,
                    high: snap.highPrice,
                    low: snap.lowPrice,
                    close: snap.closePrice
                });
                newVolumes.push({
                    time,
                    value: snap.volume,
                    color: snap.closePrice >= snap.openPrice ? CHART_COLORS.UP_TRANSPARENT : CHART_COLORS.DOWN_TRANSPARENT
                });
            });

            // Merge with existing data & Clean duplicates (using helper)
            const sortedCandles = uniqueSortData([...newCandles, ...rawDataRef.current.candles]);
            const sortedVolumes = uniqueSortData([...newVolumes, ...rawDataRef.current.volumes]);

            rawDataRef.current = { candles: sortedCandles, volumes: sortedVolumes };

            // Forward-fill gaps
            const { filledCandles, filledVolumes } = forwardFillCandles(sortedCandles, sortedVolumes, activeTimeframe);

            if (mainSeriesRef.current && volumeSeriesRef.current) {
                mainSeriesRef.current.setData(filledCandles);
                volumeSeriesRef.current.setData(filledVolumes);
            }

            if (filledCandles.length > 0) {
                const maxTime = Math.max(...filledCandles.map(c => c.time as number));
                lastCandleTimeRef.current = Math.max(lastCandleTimeRef.current, maxTime);
            }

            // If initial load doesn't fill viewport, load more
            if (!to) {
                setTimeout(() => ensureFullView(), CHART_CONSTANTS.INITIAL_FILL_DELAY_MS);
            }

        } catch (err) {
            console.error("[TradingChart] Failed to load data", err);
        } finally {
            isFetchingRef.current = false;
            setIsLoading(false);
        }
    }, [activeTimeframe]);

    const ensureFullView = useCallback(() => {
        if (!mainChartRef.current || isFetchingRef.current || !hasMoreRef.current) return;

        const timeScale = mainChartRef.current.timeScale();
        const range = timeScale.getVisibleLogicalRange();

        if (range && range.from < CHART_CONSTANTS.SCROLL_THRESHOLD) {
            const oldestCandle = rawDataRef.current.candles[0];
            if (oldestCandle) {
                const toStr = new Date((oldestCandle.time as number) * 1000).toISOString().split('.')[0];
                console.log(`[TradingChart] Infinite Scroll Triggered. Loading before: ${toStr}`);
                loadChartData(toStr);
            }
        }
    }, [loadChartData]);

    // --- Real-time Data Handling ---
    const handleWebSocketMessage = useCallback((msg: WsMessage) => {
        if (!mainSeriesRef.current || !volumeSeriesRef.current) return;

        if (isKlineEvent(msg)) {
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
                    color: msg.close >= msg.open ? CHART_COLORS.UP_TRANSPARENT : CHART_COLORS.DOWN_TRANSPARENT,
                }, isHistorical);

                if (!isHistorical) {
                    lastCandleTimeRef.current = Math.max(lastCandleTimeRef.current, candleTime);
                }
            } catch (err) {
                console.warn(`[TradingChart] Update Error ${candleTime}:`, err);
            }
        }
        else if (isTickerEvent(msg)) {
            const tickerEvent = new CustomEvent('coinflow-ticker', { detail: msg });
            window.dispatchEvent(tickerEvent);
        }
    }, [activeTimeframe]);

    const { isConnected, subscribe } = useCoinflowWebSocket(handleWebSocketMessage);

    // --- Initialization & Lifecycle ---
    useEffect(() => {
        if (!mainContainerRef.current || !volumeContainerRef.current) return;

        // Reset
        rawDataRef.current = { candles: [], volumes: [] };
        hasMoreRef.current = true;
        lastCandleTimeRef.current = 0;

        // Chart Creation
        const mainChart = createChart(mainContainerRef.current, {
            layout: { background: { type: ColorType.Solid, color: CHART_COLORS.BACKGROUND }, textColor: CHART_COLORS.TEXT },
            grid: { vertLines: { color: CHART_COLORS.GRID }, horzLines: { color: CHART_COLORS.GRID } },
            width: mainContainerRef.current.clientWidth,
            height: mainContainerRef.current.clientHeight,
            timeScale: { visible: false, minBarSpacing: CHART_CONFIG.MIN_BAR_SPACING },
            rightPriceScale: { borderColor: CHART_COLORS.BORDER, minimumWidth: CHART_CONFIG.PRICE_SCALE_WIDTH },
        });

        const mainSeries = mainChart.addSeries(CandlestickSeries, {
            upColor: CHART_COLORS.UP, downColor: CHART_COLORS.DOWN, borderVisible: false,
            wickUpColor: CHART_COLORS.UP, wickDownColor: CHART_COLORS.DOWN,
            lastValueVisible: false, priceLineVisible: false,
        });

        const volumeChart = createChart(volumeContainerRef.current, {
            layout: { background: { type: ColorType.Solid, color: CHART_COLORS.BACKGROUND }, textColor: CHART_COLORS.TEXT },
            grid: { vertLines: { color: CHART_COLORS.GRID }, horzLines: { color: CHART_COLORS.GRID } },
            width: volumeContainerRef.current.clientWidth,
            height: volumeContainerRef.current.clientHeight,
            timeScale: { timeVisible: true, secondsVisible: false, borderColor: CHART_COLORS.BORDER, minBarSpacing: CHART_CONFIG.MIN_BAR_SPACING },
            rightPriceScale: { borderColor: CHART_COLORS.BORDER, minimumWidth: CHART_CONFIG.PRICE_SCALE_WIDTH },
            leftPriceScale: { visible: false },
        });

        const volumeSeries = volumeChart.addSeries(HistogramSeries, {
            color: CHART_COLORS.UP,
            priceFormat: { type: 'custom', formatter: (p: number) => p.toFixed(4) },
        });

        mainChartRef.current = mainChart;
        volumeChartRef.current = volumeChart;
        mainSeriesRef.current = mainSeries;
        volumeSeriesRef.current = volumeSeries;

        // Sync & Scroll Sub
        const mainTimeScale = mainChart.timeScale();
        const volTimeScale = volumeChart.timeScale();

        const syncVolRange = (range: LogicalRange | null) => {
            if (range) {
                volTimeScale.setVisibleLogicalRange(range);
                if (range.from < 5) ensureFullView();
            }
        };
        const syncMainRange = (range: LogicalRange | null) => {
            if (range) mainTimeScale.setVisibleLogicalRange(range);
        };

        mainTimeScale.subscribeVisibleLogicalRangeChange(syncVolRange);
        volTimeScale.subscribeVisibleLogicalRangeChange(syncMainRange);

        // Crosshair Sync
        mainChart.subscribeCrosshairMove((param) => {
            if (param.time) volumeChart.setCrosshairPosition(0, param.time, volumeSeries);
            else volumeChart.clearCrosshairPosition();
        });

        volumeChart.subscribeCrosshairMove((param) => {
            if (param.time) mainChart.setCrosshairPosition(0, param.time, mainSeries);
            else mainChart.clearCrosshairPosition();
        });

        // Load & Fit
        loadChartData();
        mainChart.timeScale().fitContent();

        // Resize
        const handleResize = () => {
            if (mainChartRef.current && mainContainerRef.current) {
                mainChartRef.current.applyOptions({ width: mainContainerRef.current.clientWidth, height: mainContainerRef.current.clientHeight });
            }
            if (volumeChartRef.current && volumeContainerRef.current) {
                volumeChartRef.current.applyOptions({ width: volumeContainerRef.current.clientWidth, height: volumeContainerRef.current.clientHeight });
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
    }, [activeTimeframe, loadChartData, ensureFullView]);

    // WebSocket Sub
    useEffect(() => {
        if (isConnected) subscribe(CHART_CONSTANTS.DEFAULT_SYMBOL_NAME);
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
