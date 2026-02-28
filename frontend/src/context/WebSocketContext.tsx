import React, { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
import type { WsRequest } from '../types/websocket';

type MessageListener = (event: MessageEvent) => void;

interface WebSocketContextType {
    isConnected: boolean;
    sendMessage: (message: WsRequest) => void;
    addMessageListener: (listener: MessageListener) => void;
    removeMessageListener: (listener: MessageListener) => void;
}

const WebSocketContext = createContext<WebSocketContextType | null>(null);

interface WebSocketProviderProps {
    url: string;
    children: React.ReactNode;
}

export const WebSocketProvider: React.FC<WebSocketProviderProps> = ({ url, children }) => {
    const [isConnected, setIsConnected] = useState(false);
    const ws = useRef<WebSocket | null>(null);
    const reconnectTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);
    const messageQueue = useRef<string[]>([]);
    const listenersRef = useRef<Set<MessageListener>>(new Set());

    const addMessageListener = useCallback((listener: MessageListener) => {
        listenersRef.current.add(listener);
    }, []);

    const removeMessageListener = useCallback((listener: MessageListener) => {
        listenersRef.current.delete(listener);
    }, []);

    const connect = useCallback(() => {
        if (ws.current?.readyState === WebSocket.OPEN) return;

        console.log('[WS Provider] Connecting to', url);
        const socket = new WebSocket(url);

        socket.onopen = () => {
            console.log('[WS Provider] Connected');
            setIsConnected(true);

            // Flush message queue
            if (messageQueue.current.length > 0) {
                console.log(`[WS Provider] Flushing ${messageQueue.current.length} queued messages`);
                messageQueue.current.forEach(msg => socket.send(msg));
                messageQueue.current = [];
            }
        };

        socket.onclose = () => {
            console.log('[WS Provider] Disconnected');
            setIsConnected(false);
            ws.current = null;
            reconnectTimeout.current = setTimeout(() => {
                console.log('[WS Provider] Attempting reconnect...');
                connect();
            }, 3000);
        };

        socket.onerror = (error) => {
            console.error('[WS Provider] Error:', error);
        };

        socket.onmessage = (event) => {
            // Dispatch directly to all listeners — no React state involved
            listenersRef.current.forEach(listener => listener(event));
        };

        ws.current = socket;
    }, [url]);

    useEffect(() => {
        connect();
        return () => {
            if (ws.current) {
                ws.current.close();
            }
            if (reconnectTimeout.current) {
                clearTimeout(reconnectTimeout.current);
            }
        };
    }, [connect]);

    const sendMessage = useCallback((message: WsRequest) => {
        const payload = JSON.stringify(message);
        if (ws.current?.readyState === WebSocket.OPEN) {
            ws.current.send(payload);
        } else {
            console.log('[WS Provider] Socket not open, queueing message:', message);
            messageQueue.current.push(payload);
        }
    }, []);

    return (
        <WebSocketContext.Provider value={{ isConnected, sendMessage, addMessageListener, removeMessageListener }}>
            {children}
        </WebSocketContext.Provider>
    );
};

export const useWebSocketContext = () => {
    const context = useContext(WebSocketContext);
    if (!context) {
        throw new Error('useWebSocketContext must be used within a WebSocketProvider');
    }
    return context;
};

