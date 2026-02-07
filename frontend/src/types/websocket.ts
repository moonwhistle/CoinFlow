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

// Corresponds to Backend TickDto record
export interface TickDto {
    symbol: string;
    price: number;
    volume: number;
    eventTime: number;
}

// Corresponds to Backend CandleClosedEvent record
export interface CandleClosedEvent {
    symbolId: number;
    symbolCode: string;
    interval: 'M1' | 'M5' | 'M30';
    bucketTime: string; // ISO 8601 string
    open: number;
    high: number;
    low: number;
    close: number;
    volume: number;
}

export type WsMessage = TickDto | CandleClosedEvent;

// Helper Type Guard
export const isTickDto = (msg: WsMessage): msg is TickDto => {
    return 'price' in msg && 'eventTime' in msg;
};

export const isCandleClosedEvent = (msg: WsMessage): msg is CandleClosedEvent => {
    return 'symbolCode' in msg && 'bucketTime' in msg;
};
