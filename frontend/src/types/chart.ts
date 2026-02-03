export type OhlcInterval = 'M1' | 'M5' | 'M30';

export interface OhlcCandleSnapshot {
    bucketTime: string; // ISO 8601 string e.g., "2024-01-01T12:00:00"
    openPrice: number;
    highPrice: number;
    lowPrice: number;
    closePrice: number;
    volume: number;
}

export interface OhlcChartResponse {
    symbolId: number;
    interval: OhlcInterval;
    candles: OhlcCandleSnapshot[];
}
