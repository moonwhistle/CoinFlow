
import { useEffect, useRef, useState } from 'react';
import { createChart, ColorType, CandlestickSeries, HistogramSeries } from 'lightweight-charts';
import type { Time, LogicalRange, IChartApi } from 'lightweight-charts';
import { Settings, Camera, Maximize, BarChart2 } from 'lucide-react';
import './TradingChart.css';

export const TradingChart = () => {
    const mainContainerRef = useRef<HTMLDivElement>(null);
    const volumeContainerRef = useRef<HTMLDivElement>(null);

    const mainChartRef = useRef<IChartApi | null>(null);
    const volumeChartRef = useRef<IChartApi | null>(null);

    const [activeTimeframe, setActiveTimeframe] = useState('1m');

    // --- Chart Initialization with Sync ---
    useEffect(() => {
        if (!mainContainerRef.current || !volumeContainerRef.current) return;

        // 1. Initialize Main Chart (Price) - Top 75%
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
                visible: false, // Hide X-axis on main chart (shared with volume)
                minBarSpacing: 5,
            },
            rightPriceScale: {
                borderColor: '#2B2B43',
                minimumWidth: 100, // Increased to accommodate long price strings (e.g. 90000.00)
            },
            leftPriceScale: {
                visible: false,
            },
        });

        const mainSeries = mainChart.addSeries(CandlestickSeries, {
            upColor: '#26a69a',
            downColor: '#ef5350',
            borderVisible: false,
            wickUpColor: '#26a69a',
            wickDownColor: '#ef5350',
        });

        // 2. Initialize Volume Chart - Bottom 25%
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
                minimumWidth: 100, // Match main chart width exactly
            },
            leftPriceScale: {
                visible: false,
            },
        });

        const volumeSeries = volumeChart.addSeries(HistogramSeries, {
            color: '#26a69a',
            priceFormat: { type: 'volume' },
        });

        // 3. Generate Data
        const generateMockData = () => {
            const candleData = [];
            const volumeData = [];
            let time = Math.floor(Date.now() / 1000) - 6000;
            let value = 90000;

            for (let i = 0; i < 150; i++) {
                const open = value;
                const change = (Math.random() - 0.5) * 200;
                const close = open + change;
                const high = Math.max(open, close) + Math.random() * 50;
                const low = Math.min(open, close) - Math.random() * 50;
                const volume = Math.random() * 100 + 50;
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

        mainChartRef.current = mainChart;
        volumeChartRef.current = volumeChart;

        // 4. Synchronization (Range + Crosshair)
        const mainTimeScale = mainChart.timeScale();
        const volTimeScale = volumeChart.timeScale();

        // 4.1 Range Sync
        const syncVolRange = (range: LogicalRange | null) => {
            if (range) volTimeScale.setVisibleLogicalRange(range);
        };
        const syncMainRange = (range: LogicalRange | null) => {
            if (range) mainTimeScale.setVisibleLogicalRange(range);
        };

        mainTimeScale.subscribeVisibleLogicalRangeChange(syncVolRange);
        volTimeScale.subscribeVisibleLogicalRangeChange(syncMainRange);

        // 4.2 Crosshair Sync (The Cursor)
        // When hovering main, show on volume
        mainChart.subscribeCrosshairMove((param) => {
            if (param.time) {
                // Pass NaN as price to hide horizontal line on target, only show vertical (time)
                // Note: Types might require number, but specific library versions handle this differently.
                // We'll try to find the y coordinate or just pass a dummy if needed.
                // Actually, avoiding 'series' arg might allow just time? 
                // v4+ API: setCrosshairPosition(price, time, series)

                // Hack: Pass a dummy value. The volume bar is usually low, so 0 might be visible.
                // Ideally we want only vertical line. 
                // Let's use the actual volume series data? Too complex to lookup.
                // We'll accept the horizontal line at 0 for now or update it to be hidden via options if possible.
                volumeChart.setCrosshairPosition(0, param.time, volumeSeries);
            } else {
                volumeChart.clearCrosshairPosition();
            }
        });

        // When hovering volume, show on main
        volumeChart.subscribeCrosshairMove((param) => {
            if (param.time) {
                const dataPoint = param.seriesData.get(volumeSeries);
                // We can try to get the 'close' price from main series if we really wanted perfect horizontal sync
                // But for now, just syncing time is the goal.
                mainChart.setCrosshairPosition(0, param.time, mainSeries);
            } else {
                mainChart.clearCrosshairPosition();
            }
        });

        // Initial fit
        mainChart.timeScale().fitContent();

        // 5. Resize Logic
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
        // Assuming both resize together, but observing main is usually enough if wrapper is flex

        return () => {
            resizeObserver.disconnect();
            mainTimeScale.unsubscribeVisibleLogicalRangeChange(syncVolRange);
            volTimeScale.unsubscribeVisibleLogicalRangeChange(syncMainRange);
            mainChart.remove();
            volumeChart.remove();
            mainChartRef.current = null;
            volumeChartRef.current = null;
        };
    }, []);

    return (
        <div className="chart-wrapper">
            {/* Toolbar Header */}
            <div className="chart-toolbar">
                <div className="time-frame-selector">
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
                    <BarChart2 size={18} className="tool-icon" />
                    <Settings size={18} className="tool-icon" />
                    <Camera size={18} className="tool-icon" />
                    <Maximize size={18} className="tool-icon" />
                </div>
            </div>

            {/* Main Chart (Price) */}
            <div
                ref={mainContainerRef}
                className="chart-container"
                style={{ flex: 3, borderBottom: '1px solid var(--border-color)' }}
            />

            {/* Volume Chart */}
            <div
                ref={volumeContainerRef}
                className="chart-volume-container"
                style={{ flex: 1, minHeight: 0 }}
            />
        </div>
    );
};

