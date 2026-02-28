import { useEffect, useCallback, useRef } from 'react';
import { useWebSocketContext } from '../context/WebSocketContext';
import { WsCommandType, type WsMessage } from '../types/websocket';

export const useCoinflowWebSocket = (
    onMessage?: (message: WsMessage) => void
) => {
    const { isConnected, sendMessage, addMessageListener, removeMessageListener } = useWebSocketContext();

    // useRef ensures the listener always calls the LATEST onMessage callback
    // without needing to re-register the listener when onMessage changes
    const onMessageRef = useRef(onMessage);
    onMessageRef.current = onMessage;

    useEffect(() => {
        const listener = (event: MessageEvent) => {
            try {
                const data: WsMessage = JSON.parse(event.data);
                if (onMessageRef.current) {
                    onMessageRef.current(data);
                }
            } catch (error) {
                console.error('[useCoinflowWebSocket] Failed to parse message:', error);
            }
        };

        addMessageListener(listener);
        return () => {
            removeMessageListener(listener);
        };
    }, [addMessageListener, removeMessageListener]);

    const subscribe = useCallback((symbol: string) => {
        if (!isConnected) return;

        sendMessage({
            type: WsCommandType.SUBSCRIBE,
            topics: [{ symbol }]
        });
    }, [isConnected, sendMessage]);

    const unsubscribe = useCallback((symbol: string) => {
        if (!isConnected) return;

        sendMessage({
            type: WsCommandType.UNSUBSCRIBE,
            topics: [{ symbol }]
        });
    }, [isConnected, sendMessage]);

    return {
        isConnected,
        subscribe,
        unsubscribe
    };
};

