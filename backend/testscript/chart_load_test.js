/*
 * [캐싱 이슈 3종 세트 재현 스크립트]
 * 1. Stampede: 동일한 정상 종목(1번)을 짧은 시간에 집중 요청 (동시성 확인)
 * 2. Penetration: 존재하지 않는 종목(9999번)을 계속 요청 (DB 보호 확인)
 */

import http from 'k6/http';
import { check } from 'k6';

export const options = {
    scenarios: {
        // [1] Cache Stampede: 10초 동안 50명의 가상 사용자가 1초에 50번씩 동일 종목 요청
        stampede: {
            executor: 'constant-arrival-rate',
            rate: 50,
            timeUnit: '1s',
            duration: '10s',
            preAllocatedVUs: 50,
            exec: 'testStampede',
        },
        // [2] Cache Penetration: 10초 동안 50명의 가상 사용자가 1초에 50번씩 없는 종목 요청
        penetration: {
            executor: 'constant-arrival-rate',
            rate: 50,
            timeUnit: '1s',
            duration: '10s',
            preAllocatedVUs: 50,
            exec: 'testPenetration',
        },
    },
    // 전체적인 성공률 임계치 설정 (선택사항)
    thresholds: {
        http_req_failed: ['rate<0.01'], // 에러율 1% 미만 유지
        http_req_duration: ['p(95)<500'], // 95% 응답 500ms 미만 유지
    },
};

const BASE_URL = 'http://localhost:8080/api/v1/ohlc';

// [1] Stampede 시나리오
export function testStampede() {
    const res = http.get(`${BASE_URL}/1?interval=M1&candles=120`);
    check(res, { 'stampede status is 200': (r) => r.status === 200 });
}

// [2] Penetration 시나리오
export function testPenetration() {
    // 9999번 종목은 존재하지 않으므로 404 혹은 500(처리 방식에 따라) 응답 예상
    const res = http.get(`${BASE_URL}/9999?interval=M1&candles=120`);
    // API 에러 핸들링에 따라 status를 맞춰주세요.
    check(res, { 'penetration request processed': (r) => r.status !== 200 });
}
