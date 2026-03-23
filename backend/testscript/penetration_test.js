import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    scenarios: {
        // [Cache Penetration Scenario]
        // 존재하지 않는 Symbol ID를 반복해서 요청합니다.
        // 해당 데이터는 캐시에 저장되지 않기 때문에(Null Caching 미적용 시),
        // 매번 DB를 직접 조회하여 불필요한 부하가 발생하는지 확인하는 테스트입니다.
        penetration: {
            executor: 'constant-vus',
            vus: 10,
            duration: '30s',
        },
    },
};

const BASE_URL = 'http://localhost:8080/api/v1/ohlc';

export default function () {
    // 존재하지 않는 Symbol ID 9999로 요청
    const res = http.get(`${BASE_URL}/9999?interval=M1&candles=120`);
    
    check(res, {
        'penetration request processed (non-200)': (r) => r.status !== 200,
    });

    sleep(0.1); // 부하 조절
}
