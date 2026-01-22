import { useEffect } from 'react';
import { useCoinflowWebSocket } from '../hooks/useCoinflowWebSocket';

const WS_URL = 'ws://localhost:8080/ws/v1/coinflow';

export const LiveTicker = () => {
    const { isConnected, lastMessage, subscribe, unsubscribe } = useCoinflowWebSocket(WS_URL);

    useEffect(() => {
        if (isConnected) {
            subscribe('btcusdt');
        }
    }, [isConnected, subscribe]);

    return (
        <div style={{ padding: '20px', border: '1px solid #ccc', borderRadius: '8px' }}>
            <h2>Live Ticker</h2>
            <p>Status: {isConnected ? '🟢 Connected' : '🔴 Disconnected'}</p>

            {isConnected && (
                <div style={{ marginTop: '10px' }}>
                    <button onClick={() => subscribe('btcusdt')}>Subscribe btcusdt</button>
                    <button onClick={() => unsubscribe('btcusdt')} style={{ marginLeft: '10px' }}>
                        Unsubscribe btcusdt
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
