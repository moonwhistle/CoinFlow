import { useEffect, useRef, useState, useCallback } from 'react';
import { WsCommandType, type WsRequest, type TickData } from '../types/websocket';

const RECONNECT_INTERVAL = 3000; // 3 seconds

export const useCoinflowWebSocket = (url: string) => {
    const [isConnected, setIsConnected] = useState(false);
    const [lastMessage, setLastMessage] = useState<TickData | null>(null);
    const wsRef = useRef<WebSocket | null>(null);
    const timerRef = useRef<number | null>(null);
    const connectRef = useRef<() => void>(null);

    const connect = useCallback(() => {
        if (wsRef.current) return; // Prevent multiple connections

        const ws = new WebSocket(url);
        wsRef.current = ws;

        ws.onopen = () => {
            console.log('[WS] Connected');
            setIsConnected(true);
            // Clear any reconnect timers
            if (timerRef.current) {
                clearTimeout(timerRef.current);
                timerRef.current = null;
            }
        };

        ws.onmessage = (event) => {
            try {
                const data: TickData = JSON.parse(event.data);
                setLastMessage(data);
            } catch (err) {
                console.error('[WS] Failed to parse message:', err);
            }
        };

        ws.onclose = () => {
            console.log('[WS] Disconnected');
            setIsConnected(false);
            wsRef.current = null;
            // Auto-reconnect
            timerRef.current = setTimeout(() => {
                console.log('[WS] Attempting to reconnect...');
                if (connectRef.current) {
                    connectRef.current();
                }
            }, RECONNECT_INTERVAL);
        };

        ws.onerror = (error) => {
            console.error('[WS] Error:', error);
            ws.close(); // Ensure close is triggered to start reconnection logic
        };
    }, [url]);

    // Keep the ref updated with the latest connect function
    useEffect(() => {
        connectRef.current = connect;
    }, [connect]);

    const disconnect = useCallback(() => {
        if (timerRef.current) {
            clearTimeout(timerRef.current);
            timerRef.current = null;
        }
        if (wsRef.current) {
            // Prevent auto-reconnect by overwriting onclose
            wsRef.current.onclose = null;
            wsRef.current.close();
            wsRef.current = null;
        }
        setIsConnected(false);
    }, []);

    const sendMessage = useCallback((message: object) => {
        if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
            wsRef.current.send(JSON.stringify(message));
        } else {
            console.warn('[WS] Cannot send message, not connected');
        }
    }, []);

    const subscribe = useCallback((symbol: string) => {
        const request: WsRequest = {
            type: WsCommandType.SUBSCRIBE,
            topics: [{ symbol }],
        };
        sendMessage(request);
    }, [sendMessage]);

    const unsubscribe = useCallback((symbol: string) => {
        const request: WsRequest = {
            type: WsCommandType.UNSUBSCRIBE,
            topics: [{ symbol }],
        };
        sendMessage(request);
    }, [sendMessage]);

    useEffect(() => {
        connect();
        return () => {
            disconnect();
        };
    }, [connect, disconnect]);

    return {
        isConnected,
        lastMessage,
        subscribe,
        unsubscribe,
    };
};
