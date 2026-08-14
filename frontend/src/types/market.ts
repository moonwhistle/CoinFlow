export interface MarketStats24h {
    symbolId: number;
    symbol: string;
    windowStartEpochMillis: number;
    asOfEpochMillis: number;
    currentCandleStartEpochSeconds: number | null;
    currentCandleVolume: number;
    openPrice: number;
    currentPrice: number;
    highPrice: number;
    lowPrice: number;
    volume: number;
    changePercent: number;
}
