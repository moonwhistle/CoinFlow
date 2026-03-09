#!/bin/bash
# 시각적 확인을 위해 1분 20초(80초) 전의 시간으로 틱을 주입합니다.
# 가격(150000)을 아주 높게 줘서 이전(닫힌) 캔들의 고점 위 꼬리가 비정상적으로 치솟는 것을 눈으로 확인합니다.

PAST_TIME=$(date -v-80S -u +"%Y-%m-%dT%H:%M:%SZ")

docker exec -i redis-local /bin/sh -c "redis-cli XADD 'tick:raw' '*' symbol 'btcusdt' price '150000' quantity '10' eventTime '$PAST_TIME'"

echo "======================================"
echo "[SUCCESS] 시뮬레이션용 Late Tick 발송 완료!"
echo "- 대상 코인 : btcusdt"
echo "- 강제 주입 시간 (UTC): $PAST_TIME (약 80초 전)"
echo "- 가격        : 150000"
echo "======================================"
echo "프론트엔드 차트(M1)를 보면 1~2칸 이전 캔들이 갑자기 위로 길게 솟아오른 것을 볼 수 있습니다!"
