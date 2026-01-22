import { useEffect } from 'react';
import { useCoinflowWebSocket } from '../hooks/useCoinflowWebSocket';

const WS_URL = 'ws://localhost:8080/ws/v1/coinflow';

export const LiveTicker = () => {
    const { isConnected, lastMessage, subscribe, unsubscribe } = useCoinflowWebSocket(WS_URL);

    useEffect(() => {
        if (isConnected) {
            subscribe('BTC/KRW');
        }
    }, [isConnected, subscribe]);

    return (
        <div style={{ padding: '20px', border: '1px solid #ccc', borderRadius: '8px' }}>
            <h2>Live Ticker</h2>
            <p>Status: {isConnected ? '🟢 Connected' : '🔴 Disconnected'}</p>

            {isConnected && (
                <div style={{ marginTop: '10px' }}>
                    <button onClick={() => subscribe('BTC/KRW')}>Subscribe BTC/KRW</button>
                    <button onClick={() => unsubscribe('BTC/KRW')} style={{ marginLeft: '10px' }}>
                        Unsubscribe BTC/KRW
                    </button>
                </div>
            )}

            <div style={{ marginTop: '20px' }}>
                <h3>Last Tick Data:</h3>
                {lastMessage ? (
                    <pre>{JSON.stringify(lastMessage, null, 2)}</pre>
                ) : (
                    <p>No data received yet...</p>
                )}
            </div>
        </div>
    );
};
