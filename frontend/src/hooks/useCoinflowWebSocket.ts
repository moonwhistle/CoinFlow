import { useEffect, useCallback, useState } from 'react';
import { useWebSocketContext } from '../context/WebSocketContext';
import { WsCommandType, type WsMessage } from '../types/websocket';

export const useCoinflowWebSocket = (
    onMessage?: (message: WsMessage) => void
) => {
    const { isConnected, sendMessage, lastMessage } = useWebSocketContext();

    const [parsedMessage, setParsedMessage] = useState<WsMessage | null>(null);

    // Handle incoming messages from context
    useEffect(() => {
        if (lastMessage) {
            try {
                const data: WsMessage = JSON.parse(lastMessage.data);

                setParsedMessage(data);
                if (onMessage) {
                    onMessage(data);
                }
            } catch (error) {
                console.error('[useCoinflowWebSocket] Failed to parse message:', error);
            }
        }
    }, [lastMessage, onMessage]);

    // ... (subscribe/unsubscribe) ...



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
        lastMessage: parsedMessage,
        subscribe,
        unsubscribe
    };
};
