export const WsCommandType = {
    SUBSCRIBE: 'SUBSCRIBE',
    UNSUBSCRIBE: 'UNSUBSCRIBE',
} as const;

export type WsCommandType = typeof WsCommandType[keyof typeof WsCommandType];

export interface WsSubscription {
    symbol: string;
}

export interface WsRequest {
    type: WsCommandType;
    topics: WsSubscription[];
}

/**
 * Kline (candlestick) event from WebSocket.
 * Matches Binance kline stream format.
 * Sent every ~1 second with current candle state.
 * When closed=true, this is the final value for the candle.
 */
export interface KlineEvent {
    symbol: string;
    interval: 'M1' | 'M5' | 'M30';
    startTime: number;    // epoch seconds (candle start)
    closeTime: number;    // epoch seconds (candle end)
    open: number;
    high: number;
    low: number;
    close: number;
    volume: number;
    trades: number;
    closed: boolean;      // true = candle is finalized
}

export type WsMessage = KlineEvent;

// Helper Type Guard
export const isKlineEvent = (msg: WsMessage): msg is KlineEvent => {
    return 'startTime' in msg && 'interval' in msg;
};

