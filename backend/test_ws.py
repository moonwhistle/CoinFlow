import asyncio
import websockets
import json

async def test_subscription():
    uri = "ws://localhost:8080/ws/v1/coinflow"
    async with websockets.connect(uri) as websocket:
        # Subscribe
        sub_msg = {
            "type": "SUBSCRIBE",
            "topics": [{"symbol": "BTC/KRW"}]
        }
        await websocket.send(json.dumps(sub_msg))
        print(f"Sent: {sub_msg}")

        # Wait for messages
        try:
            while True:
                response = await asyncio.wait_for(websocket.recv(), timeout=10)
                print(f"Received: {response}")
        except asyncio.TimeoutError:
            print("No more messages")

asyncio.run(test_subscription())
