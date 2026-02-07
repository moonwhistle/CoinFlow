import { useEffect, useRef, useState, useCallback } from 'react';
import { createChart, ColorType, CandlestickSeries, HistogramSeries } from 'lightweight-charts';
import type { Time, LogicalRange, IChartApi, ISeriesApi } from 'lightweight-charts';
import { Settings, Camera, Maximize, BarChart2 } from 'lucide-react';
import { useCoinflowWebSocket } from '../../hooks/useCoinflowWebSocket';
import { CHART_COLORS, CHART_CONFIG } from '../../constants/chart';
import { aggregateTickToCandle } from '../../utils/chartHelpers';
import type { ChartCandle, VolumeBar } from '../../utils/chartHelpers';
import { getOhlcData } from '../../api/ohlcApi';
import type { OhlcInterval, OhlcCandleSnapshot } from '../../types/chart';
import { type WsMessage, isTickDto, isCandleClosedEvent } from '../../types/websocket';
import './TradingChart.css';

export const TradingChart = () => {
    const mainContainerRef = useRef<HTMLDivElement>(null);
    const volumeContainerRef = useRef<HTMLDivElement>(null);

    // Refs for chart APIs to access inside effects/callbacks without re-rendering
    const mainChartRef = useRef<IChartApi | null>(null);
    const volumeChartRef = useRef<IChartApi | null>(null);
    const mainSeriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);
    const volumeSeriesRef = useRef<ISeriesApi<"Histogram"> | null>(null);

    // Default to M1 as per req
    const [activeTimeframe, setActiveTimeframe] = useState<OhlcInterval>('M1');
    const [isLoading, setIsLoading] = useState(true);

    // State to track the current accumulating candle
    const currentCandleRef = useRef<ChartCandle | null>(null);
    const currentVolumeRef = useRef<VolumeBar | null>(null);

    // --- Real-time Data Handling (Performance Optimized) ---
    const handleWebSocketMessage = useCallback((msg: WsMessage) => {
        if (!mainSeriesRef.current || !volumeSeriesRef.current) return;

        if (isTickDto(msg)) {
            // 1. Optimistic Update (Tick)
            const { candle, volume } = aggregateTickToCandle(
                msg,
                currentCandleRef.current,
                currentVolumeRef.current,
                CHART_COLORS.UP_TRANSPARENT,
                CHART_COLORS.DOWN_TRANSPARENT,
                activeTimeframe
            );

            if (candle && volume) {
                mainSeriesRef.current.update(candle);
                volumeSeriesRef.current.update(volume);

                currentCandleRef.current = candle;
                currentVolumeRef.current = volume;
            }
        } else if (isCandleClosedEvent(msg)) {
            // 2. Server Correction (CandleClosed)
            // Filter by active timeframe
            if (msg.interval !== activeTimeframe) {
                return;
            }

            // Parse bucketTime (LocalDateTime string) to chart time
            const bucketTime = (new Date(msg.bucketTime).getTime() / 1000) as Time;

            // Correction Candle
            const correctedCandle: ChartCandle = {
                time: bucketTime,
                open: msg.open,
                high: msg.high,
                low: msg.low,
                close: msg.close,
            };

            const correctedVolume: VolumeBar = {
                time: bucketTime,
                value: msg.volume,
                color: msg.close >= msg.open ? CHART_COLORS.UP_TRANSPARENT : CHART_COLORS.DOWN_TRANSPARENT,
            };

            // Apply Correction
            // Note: If we have already moved to the next candle (new tick arrived), update() for a past candle might fail in lightweight-charts.
            // But since this event fires exactly at the close, it typically arrives before or very close to the first tick of the next candle.
            try {
                mainSeriesRef.current.update(correctedCandle);
                volumeSeriesRef.current.update(correctedVolume);
                console.log(`[Correction] Applied for ${msg.symbolCode} at ${msg.bucketTime}`);
            } catch (e) {
                console.warn(`[Correction] Skipped for ${msg.bucketTime} due to time regression (Candle already moved forward)`);
            }
        }
    }, [activeTimeframe]);

    // WebSocket Hook with Callback
    const { isConnected, subscribe } = useCoinflowWebSocket(
        'ws://localhost:8080/ws/v1/coinflow',
        { onMessage: handleWebSocketMessage }
    );

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

        // 3. Initial Data Load
        const loadInitialData = async () => {
            setIsLoading(true);
            try {
                // TODO: Symbol ID is hardcoded to 1 for now (assuming BTCUSDT = 1)
                const response = await getOhlcData(1, activeTimeframe, 120);

                const candles: ChartCandle[] = [];
                const volumes: VolumeBar[] = [];

                response.candles.forEach((snap: OhlcCandleSnapshot) => {
                    const time = (new Date(snap.bucketTime).getTime() / 1000) as Time;

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

                mainSeries.setData(candles);
                volumeSeries.setData(volumes);

                // Update refs for real-time updates
                if (candles.length > 0) {
                    currentCandleRef.current = candles[candles.length - 1];
                    currentVolumeRef.current = volumes[volumes.length - 1];
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
            console.log("Subscribing to BTCUSDT...");
            subscribe('BTCUSDT');
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

            <div
                ref={volumeContainerRef}
                className="chart-volume-container"
                style={{ flex: 1, minHeight: 0 }}
            />
        </div>
    );
};
