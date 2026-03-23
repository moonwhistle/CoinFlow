import http from 'k6/http';
import { check } from 'k6';

export const options = {
    scenarios: {
        // [수정된 스탬피드 시나리오]
        // 50명의 사용자가 시작과 동시에 "한꺼번에" 요청을 날립니다.
        stampede: {
            executor: 'per-vu-iterations',
            vus: 50,
            iterations: 1,
            maxDuration: '5s',
            exec: 'testStampede',
        },
        // 관통 시나리오
        penetration: {
            executor: 'constant-arrival-rate',
            rate: 20,
            timeUnit: '1s',
            duration: '5s',
            preAllocatedVUs: 20,
            exec: 'testPenetration',
        },
    },
};

const BASE_URL = 'http://localhost:8080/api/v1/ohlc';

export function testStampede() {
    const res = http.get(`${BASE_URL}/1?interval=M1&candles=120`);
    check(res, { 'stampede status is 200': (r) => r.status === 200 });
}

export function testPenetration() {
    const res = http.get(`${BASE_URL}/9999?interval=M1&candles=120`);
    check(res, { 'penetration request processed': (r) => r.status !== 200 });
}
