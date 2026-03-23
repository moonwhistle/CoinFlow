import http from 'k6/http';
import { check } from 'k6';

export const options = {
    scenarios: {
        // [Cache Stampede Scenario]
        // 50명의 가상 사용자가 동시에 동일한 데이터를 요청합니다.
        // 캐시가 비어있는 상태에서 동시에 요청이 몰릴 때, 
        // 중복된 DB 조회가 발생하는지(Stampede) 확인하기 위한 테스트입니다.
        stampede: {
            executor: 'per-vu-iterations',
            vus: 50,
            iterations: 1,
            maxDuration: '10s',
        },
    },
};

const BASE_URL = 'http://localhost:8080/api/v1/ohlc';

export default function () {
    // 실존하는 Symbol ID 1번에 대한 차트 조회
    const res = http.get(`${BASE_URL}/1?interval=M1&candles=120`);
    
    check(res, {
        'is status 200': (r) => r.status === 200,
    });
}
