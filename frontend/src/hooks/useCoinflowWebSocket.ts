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
                // We know this is WsMessage type from context, but we need to safely mutate or cast it
                const data: WsMessage = JSON.parse(lastMessage.data);

                // Downscale volume for real-time data
                if ('volume' in data && typeof data.volume === 'number') {
                    data.volume = data.volume / 100000000;
                }

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
