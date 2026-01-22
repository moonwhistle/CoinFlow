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

export interface TickData {
    symbol: string;
    price: string;
    volume: string;
    [key: string]: string; // For flexibility
}
