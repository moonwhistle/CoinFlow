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

export interface TickerEvent {
    symbol: string;
    price: number;
    volume: number;
    eventTime: number; // epoch milliseconds
}

export type WsMessage = KlineEvent | TickerEvent;

// Helper Type Guards
export const isKlineEvent = (msg: WsMessage): msg is KlineEvent => {
    return 'interval' in msg && 'startTime' in msg;
};

export const isTickerEvent = (msg: WsMessage): msg is TickerEvent => {
    return 'price' in msg && 'eventTime' in msg;
};

