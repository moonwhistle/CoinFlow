import { createContext } from 'react';
import type { WsRequest } from '../types/websocket';

export type MessageListener = (event: MessageEvent) => void;

export interface WebSocketContextType {
    isConnected: boolean;
    sendMessage: (message: WsRequest) => void;
    addMessageListener: (listener: MessageListener) => void;
    removeMessageListener: (listener: MessageListener) => void;
}

export const WebSocketContext = createContext<WebSocketContextType | null>(null);
