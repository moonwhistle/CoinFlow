import asyncio
import websockets
import json

async def test_subscription():
    uri = "ws://localhost:8080/ws/v1/coinflow"
    async with websockets.connect(uri) as websocket:
        print("[Client] Connected to WebSocket")

        # Subscribe message
        subscribe_msg = {
            "type": "SUBSCRIBE",
            "topics": [
                {"symbol": "btcusdt"}
            ]
        }
        
        print(f"[Client] Sending: {json.dumps(subscribe_msg)}")
        await websocket.send(json.dumps(subscribe_msg))

        # Listen for messages
        print("[Client] Listening for messages...")
        try:
            while True:
                response = await websocket.recv()
                print(f"[Client] Received: {response}")
                
                data = json.loads(response)
                if 'symbol' in data and data['symbol'] == 'btcusdt':
                    print("[SUCCESS] Received tick for btcusdt!")
                    break
        except Exception as e:
            print(f"[Client] Error: {e}")

if __name__ == "__main__":
    asyncio.run(test_subscription())
