import type { Time } from 'lightweight-charts';
import type { TickData } from '../types/websocket';
import type { OhlcInterval } from '../types/chart';

export interface ChartCandle {
    time: Time;
    open: number;
    high: number;
    low: number;
    close: number;
}

export interface VolumeBar {
    time: Time;
    value: number;
    color: string;
}

/**
 * Tick 데이터를 1분봉 캔들로 병합하거나 새로운 캔들을 생성합니다.
 * 백엔드 API 연동 전까지 클라이언트 사이드 집계를 수행합니다.
 */
export const aggregateTickToCandle = (
    tick: TickData,
    currentCandle: ChartCandle | null,
    currentVolume: VolumeBar | null,
    upColor: string,
    downColor: string,
    interval: OhlcInterval = 'M1'
) => {
    const price = parseFloat(tick.price);
    const quantity = parseFloat(tick.quantity);
    const timestamp = parseInt(tick.eventTime);

    // Determine duration in seconds based on interval
    let duration = 60;
    if (interval === 'M5') duration = 300;
    else if (interval === 'M30') duration = 1800;

    // Round to nearest interval start time
    // timestamp is in ms, so divide by 1000 first, or convert duration to ms
    // Logic: Floor(timestamp_ms / duration_ms) * duration_seconds_converted_to_chart_time
    // Wait, chart uses Seconds.

    const timestampSec = Math.floor(timestamp / 1000);
    const candleTime = (Math.floor(timestampSec / duration) * duration) as Time;

    let nextCandle = currentCandle ? { ...currentCandle } : null;
    let nextVolume = currentVolume ? { ...currentVolume } : null;
    let isNewCandle = false;

    // Check if we moved to a new minute or initialized
    if (!nextCandle || candleTime > nextCandle.time) {
        isNewCandle = true;
        // New Candle
        nextCandle = {
            time: candleTime,
            open: price,
            high: price,
            low: price,
            close: price,
        };
        nextVolume = {
            time: candleTime,
            value: quantity,
            color: upColor,
        };
    } else {
        // Update Existing Candle
        nextCandle.high = Math.max(nextCandle.high, price);
        nextCandle.low = Math.min(nextCandle.low, price);
        nextCandle.close = price;

        // Accumulate volume
        if (nextVolume) {
            nextVolume.value += quantity;
            // Determine color based on candle direction
            const isUp = nextCandle.close >= nextCandle.open;
            nextVolume.color = isUp ? upColor : downColor;
        }
    }

    return {
        candle: nextCandle,
        volume: nextVolume,
        isNewCandle
    };
};

/**
 * 초기 차트 데이터를 위한 Mock 데이터 생성기
 * (추후 백엔드 Historical Data API로 대체 예정)
 */
export const generateMockData = (count: number = 100) => {
    const candleData: ChartCandle[] = [];
    const volumeData: VolumeBar[] = [];

    // End 1 minute ago so live data takes over naturally
    let time = Math.floor(Date.now() / 1000) - (60 * count);
    let value = 90000;

    for (let i = 0; i < count; i++) {
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
