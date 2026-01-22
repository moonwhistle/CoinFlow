export enum WsCommandType {
    SUBSCRIBE = 'SUBSCRIBE',
    UNSUBSCRIBE = 'UNSUBSCRIBE',
}

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
