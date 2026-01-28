import { useEffect, useRef, useState } from 'react';
import { createChart, ColorType, CandlestickSeries, HistogramSeries } from 'lightweight-charts';
import type { Time, LogicalRange, IChartApi, ISeriesApi } from 'lightweight-charts';
import { Settings, Camera, Maximize, BarChart2 } from 'lucide-react';
import { useCoinflowWebSocket } from '../../hooks/useCoinflowWebSocket';
import './TradingChart.css';

interface ChartCandle {
    time: Time;
    open: number;
    high: number;
    low: number;
    close: number;
}

interface VolumeBar {
    time: Time;
    value: number;
    color: string;
}

export const TradingChart = () => {
    const mainContainerRef = useRef<HTMLDivElement>(null);
    const volumeContainerRef = useRef<HTMLDivElement>(null);

    // Refs for chart APIs to access inside effects/callbacks without re-rendering
    const mainChartRef = useRef<IChartApi | null>(null);
    const volumeChartRef = useRef<IChartApi | null>(null);
    const mainSeriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);
    const volumeSeriesRef = useRef<ISeriesApi<"Histogram"> | null>(null);

    const [activeTimeframe, setActiveTimeframe] = useState('1m');

    // WebSocket Hook
    const { isConnected, lastMessage, subscribe } = useCoinflowWebSocket('ws://localhost:8080/ws/connect');

    // State to track the current accumulating candle
    const currentCandleRef = useRef<ChartCandle | null>(null);
    const currentVolumeRef = useRef<VolumeBar | null>(null);

    // --- Chart Initialization ---
    useEffect(() => {
        if (!mainContainerRef.current || !volumeContainerRef.current) return;

        // 1. Initialize Main Chart (Price)
        const mainChart = createChart(mainContainerRef.current, {
            layout: {
                background: { type: ColorType.Solid, color: 'transparent' },
                textColor: '#9CA3AF',
            },
            grid: {
                vertLines: { color: 'rgba(42, 46, 57, 0.5)' },
                horzLines: { color: 'rgba(42, 46, 57, 0.5)' },
            },
            width: mainContainerRef.current.clientWidth,
            height: mainContainerRef.current.clientHeight,
            timeScale: {
                visible: false,
                minBarSpacing: 5,
            },
            rightPriceScale: {
                borderColor: '#2B2B43',
                minimumWidth: 100,
            },
        });

        const mainSeries = mainChart.addSeries(CandlestickSeries, {
            upColor: '#26a69a',
            downColor: '#ef5350',
            borderVisible: false,
            wickUpColor: '#26a69a',
            wickDownColor: '#ef5350',
        });

        // 2. Initialize Volume Chart
        const volumeChart = createChart(volumeContainerRef.current, {
            layout: {
                background: { type: ColorType.Solid, color: 'transparent' },
                textColor: '#9CA3AF',
            },
            grid: {
                vertLines: { color: 'rgba(42, 46, 57, 0.5)' },
                horzLines: { color: 'rgba(42, 46, 57, 0.5)' },
            },
            width: volumeContainerRef.current.clientWidth,
            height: volumeContainerRef.current.clientHeight,
            timeScale: {
                timeVisible: true,
                secondsVisible: false,
                borderColor: '#2B2B43',
                minBarSpacing: 5,
            },
            rightPriceScale: {
                borderColor: '#2B2B43',
                minimumWidth: 100,
            },
            leftPriceScale: {
                visible: false,
            },
        });

        const volumeSeries = volumeChart.addSeries(HistogramSeries, {
            color: '#26a69a',
            priceFormat: { type: 'volume' },
        });

        // 3. Generate Mock Data (ending at current time for smooth transition)
        const generateMockData = () => {
            const candleData: ChartCandle[] = [];
            const volumeData: VolumeBar[] = [];

            // End 1 minute ago so live data takes over
            let time = Math.floor(Date.now() / 1000) - (60 * 100);
            let value = 90000;

            for (let i = 0; i < 100; i++) {
                const open = value;
                const change = (Math.random() - 0.5) * 50;
                const close = open + change;
                const high = Math.max(open, close) + Math.random() * 10;
                const low = Math.min(open, close) - Math.random() * 10;
                const volume = Math.random() * 10 + 5;
                const isUp = close >= open;
                const timePoint = Math.floor(time / 60) * 60 as Time;

                candleData.push({ time: timePoint, open, high, low, close });
                volumeData.push({
                    time: timePoint,
                    value: volume,
                    color: isUp ? 'rgba(38, 166, 154, 0.5)' : 'rgba(239, 83, 80, 0.5)'
                });
                value = close;
                time += 60;
            }
            return { candleData, volumeData };
        };

        const { candleData, volumeData } = generateMockData();
        mainSeries.setData(candleData);
        volumeSeries.setData(volumeData);

        // Set Refs
        mainChartRef.current = mainChart;
        volumeChartRef.current = volumeChart;
        mainSeriesRef.current = mainSeries;
        volumeSeriesRef.current = volumeSeries;

        // Initialize current candle ref with the last mock candle to allow continuation
        const lastCandle = candleData[candleData.length - 1];
        const lastVol = volumeData[volumeData.length - 1];
        currentCandleRef.current = lastCandle;
        currentVolumeRef.current = lastVol;

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

    // --- Real-time Data Handling ---
    useEffect(() => {
        if (isConnected) {
            console.log("Subscribing to BTCUSDT...");
            subscribe('BTCUSDT');
        }
    }, [isConnected, subscribe]);

    useEffect(() => {
        if (!lastMessage) return;
        if (!mainSeriesRef.current || !volumeSeriesRef.current) return;

        const tick = lastMessage;
        const price = parseFloat(tick.price);
        const quantity = parseFloat(tick.quantity);
        const timestamp = parseInt(tick.eventTime); // raw ms timestamp

        // Round to 1-minute candle time (seconds)
        const candleTime = (Math.floor(timestamp / 60000) * 60) as Time;

        let currentCandle = currentCandleRef.current;
        let currentVol = currentVolumeRef.current;

        // Check if we moved to a new minute
        if (!currentCandle || candleTime > currentCandle.time) {
            // New Candle
            currentCandle = {
                time: candleTime,
                open: price,
                high: price,
                low: price,
                close: price,
            };
            currentVol = {
                time: candleTime,
                value: quantity,
                color: 'rgba(38, 166, 154, 0.5)', // Default Green
            };
        } else {
            // Update Existing Candle
            currentCandle.high = Math.max(currentCandle.high, price);
            currentCandle.low = Math.min(currentCandle.low, price);
            currentCandle.close = price;

            // Accumulate volume
            if (currentVol) {
                currentVol.value += quantity;
                // Determine color based on candle direction
                const isUp = currentCandle.close >= currentCandle.open;
                currentVol.color = isUp ? 'rgba(38, 166, 154, 0.5)' : 'rgba(239, 83, 80, 0.5)';
            }
        }

        // Apply Update
        mainSeriesRef.current.update(currentCandle);
        if (currentVol) {
            volumeSeriesRef.current.update(currentVol);
        }

        // Update Refs
        currentCandleRef.current = currentCandle;
        currentVolumeRef.current = currentVol;

    }, [lastMessage]);

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
