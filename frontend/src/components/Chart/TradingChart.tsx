import { useEffect, useRef, useState } from 'react';
import { createChart, ColorType, CandlestickSeries } from 'lightweight-charts';
import type { Time } from 'lightweight-charts';
import { Settings, Camera, Maximize, BarChart2 } from 'lucide-react';
import './TradingChart.css';

export const TradingChart = () => {
    const chartContainerRef = useRef<HTMLDivElement>(null);
    const chartInstanceRef = useRef<any>(null);
    const [activeTimeframe, setActiveTimeframe] = useState('1m');

    // --- Chart Initialization with Resize Observer ---
    useEffect(() => {
        if (!chartContainerRef.current) return;

        const container = chartContainerRef.current;

        // 1. Create Chart
        const chart = createChart(container, {
            layout: {
                background: { type: ColorType.Solid, color: 'transparent' }, // Transparent to use CSS bg
                textColor: '#9CA3AF',
            },
            grid: {
                vertLines: { color: 'rgba(42, 46, 57, 0.5)' },
                horzLines: { color: 'rgba(42, 46, 57, 0.5)' },
            },
            width: container.clientWidth,
            height: container.clientHeight,
            timeScale: {
                timeVisible: true,
                secondsVisible: false,
                borderColor: '#2B2B43',
            },
            rightPriceScale: {
                borderColor: '#2B2B43',
            },
        });

        // 2. Add Series
        const mainSeries = chart.addSeries(CandlestickSeries, {
            upColor: '#26a69a',
            downColor: '#ef5350',
            borderVisible: false,
            wickUpColor: '#26a69a',
            wickDownColor: '#ef5350'
        });

        // Mock Data specifically designed to look populated (100 candles)
        const generateMockData = () => {
            const data = [];
            let time = Math.floor(Date.now() / 1000) - 6000; // 100 mins ago
            let value = 90000;
            for (let i = 0; i < 100; i++) {
                const open = value;
                const change = (Math.random() - 0.5) * 200;
                const close = open + change;
                const high = Math.max(open, close) + Math.random() * 50;
                const low = Math.min(open, close) - Math.random() * 50;
                // Round to minute
                const timePoint = Math.floor(time / 60) * 60;
                data.push({ time: timePoint as Time, open, high, low, close });
                value = close;
                time += 60;
            }
            return data;
        };

        mainSeries.setData(generateMockData());
        chart.timeScale().fitContent();

        chartInstanceRef.current = chart;

        // 3. Resize Logic
        const handleResize = () => {
            if (chartInstanceRef.current && container) {
                chartInstanceRef.current.applyOptions({
                    width: container.clientWidth,
                    height: container.clientHeight
                });
            }
        };

        const resizeObserver = new ResizeObserver(() => handleResize());
        resizeObserver.observe(container);

        return () => {
            resizeObserver.disconnect();
            chart.remove();
            chartInstanceRef.current = null;
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

            {/* Chart Container */}
            <div ref={chartContainerRef} className="chart-container" />
        </div>
    );
};
