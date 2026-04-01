import type { Time } from 'lightweight-charts';
import type { TickerEvent } from '../types/websocket';
import type { OhlcInterval } from '../types/chart';
import { CHART_COLORS } from '../constants/chart';

export interface ChartCandle {
    time: Time;
    open: number;
    high: number;
    low: number;
    close: number;
    // Per-candle color overrides (for ghost candles)
    color?: string;
    borderColor?: string;
    wickUpColor?: string;
    wickDownColor?: string;
}

export interface VolumeBar {
    time: Time;
    value: number;
    color: string;
}

/**
 * Tick 데이터를 인터벌별 캔들로 병합하거나 새로운 캔들을 생성합니다.
 */
export const aggregateTickToCandle = (
    tick: TickerEvent,
    currentCandle: ChartCandle | null,
    currentVolume: VolumeBar | null,
    upColor: string,
    downColor: string,
    interval: OhlcInterval = 'M1'
) => {
    const price = tick.price;
    const quantity = tick.volume;
    const timestamp = tick.eventTime;

    // Determine duration in seconds based on interval
    let duration = 60;
    if (interval === 'M5') duration = 300;
    else if (interval === 'M30') duration = 1800;

    const timestampSec = Math.floor(timestamp / 1000);
    const candleTime = (Math.floor(timestampSec / duration) * duration) as Time;

    let nextCandle = currentCandle ? { ...currentCandle } : null;
    let nextVolume = currentVolume ? { ...currentVolume } : null;
    let isNewCandle = false;

    // Check if we moved to a new minute or initialized
    if (!nextCandle || candleTime > nextCandle.time) {
        isNewCandle = true;
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
 * 캔들 데이터의 시간 갭을 Forward-Fill로 채웁니다.
 * 거래가 없던 분에는 이전 close 가격으로 flat 캔들을 생성하고,
 * 반투명(ghost) 스타일을 적용합니다.
 *
 * @param candles - 정렬된 캔들 배열
 * @param volumes - 정렬된 볼륨 배열 (candles와 1:1 대응)
 * @param interval - 차트 인터벌 ('M1', 'M5', 'M30')
 */
export const forwardFillCandles = (
    candles: ChartCandle[],
    volumes: VolumeBar[],
    interval: OhlcInterval = 'M1'
): { filledCandles: ChartCandle[]; filledVolumes: VolumeBar[] } => {
    if (candles.length < 2) return { filledCandles: candles, filledVolumes: volumes };

    let durationSec = 60;
    if (interval === 'M5') durationSec = 300;
    else if (interval === 'M30') durationSec = 1800;

    const filledCandles: ChartCandle[] = [];
    const filledVolumes: VolumeBar[] = [];

    for (let i = 0; i < candles.length; i++) {
        // 첫 캔들 전에는 채우기 불가
        if (i > 0) {
            const prevTime = candles[i - 1].time as number;
            const currTime = candles[i].time as number;
            const prevClose = candles[i - 1].close;

            // 갭이 있으면 ghost 캔들로 채우기
            for (let t = prevTime + durationSec; t < currTime; t += durationSec) {
                filledCandles.push({
                    time: t as Time,
                    open: prevClose,
                    high: prevClose,
                    low: prevClose,
                    close: prevClose,
                    color: CHART_COLORS.GHOST,
                    borderColor: CHART_COLORS.GHOST,
                    wickUpColor: CHART_COLORS.GHOST_WICK,
                    wickDownColor: CHART_COLORS.GHOST_WICK,
                });
                filledVolumes.push({
                    time: t as Time,
                    value: 0,
                    color: CHART_COLORS.GHOST,
                });
            }
        }

        filledCandles.push(candles[i]);
        filledVolumes.push(volumes[i]);
    }

    return { filledCandles, filledVolumes };
};

/**
 * 차트 데이터를 시간순으로 정렬하고 중복된 시간을 제거합니다. (DRY)
 */
export const uniqueSortData = <T extends { time: Time }>(data: T[]): T[] => {
    const uniqueMap = new Map<number, T>();
    data.forEach(item => uniqueMap.set(item.time as number, item));
    
    return Array.from(uniqueMap.values()).sort((a, b) => (a.time as number) - (b.time as number));
};

