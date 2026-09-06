// 동시 요청 수(VUS)를 고정하고 일정 시간(DURATION) 동안 지속 부하를 걸어
// p95/p99 지연시간과 TPS(초당 처리량)를 측정한다.
// bash 오케스트레이션(scripts/concurrency-load-test.sh)이 VUS 값을 바꿔가며 이 스크립트를
// 반복 호출하고, 그 사이 CPU·커넥션 풀 사용량은 /actuator/metrics를 별도로 폴링해서 모은다.
//
// 단독 실행 예:
//   BASE_URL=http://localhost:8080 VUS=50 DURATION=15s TAIL_BASE=5000 \
//     SUMMARY_FILE=/tmp/result.json k6 run scripts/concurrency-k6-test.js

import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TAIL_BASE = parseInt(__ENV.TAIL_BASE || '5000', 10);

const VUS = parseInt(__ENV.VUS || '10', 10);
const DURATION = __ENV.DURATION || '15s';
// RAMP_SECONDS를 주면 0명에서 VUS명까지 그 시간에 걸쳐 서서히 붙인다(실제 트래픽에 가까움).
// 생략하면 기존처럼 constant-vus — 테스트 시작 순간 VUS명이 한꺼번에 접속을 시도한다
// (이 순간 버스트가 OS 커널의 listen backlog를 넘기면 "connection reset by peer"가 남).
const RAMP_SECONDS = __ENV.RAMP_SECONDS ? parseInt(__ENV.RAMP_SECONDS, 10) : 0;

export const options = {
  scenarios: {
    search:
      RAMP_SECONDS > 0
        ? {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
              { duration: `${RAMP_SECONDS}s`, target: VUS },
              { duration: DURATION, target: VUS },
            ],
          }
        : {
            executor: 'constant-vus',
            vus: VUS,
            duration: DURATION,
          },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

function pad(n) {
  return n < 10 ? '0' + n : '' + n;
}

function dateFromOffset(offsetDays) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export default function () {
  // 요청마다 유니크한 날짜를 써서 availabilityCache를 절대 히트하지 않게 한다 — 캐시에
  // 걸리면 mock-supplier를 아예 안 불러서 동시성에 따른 지연 변화를 관찰할 수 없다.
  // constant-vus + duration 실행기에서는 VU 하나가 테스트 시간 내내 반복 실행되므로
  // __ITER이 계속 커진다 — 배율(2000)은 그 최대 반복 횟수보다 넉넉히 크면 충분하고,
  // 너무 크게 잡으면(예: 100000) 오프셋 누적으로 연도가 5자리를 넘어 LocalDate 파싱이
  // 깨져서 그 요청이 실제 지연 없이 즉시 에러로 끝나 측정을 완전히 왜곡시킨다.
  const offset = TAIL_BASE + __VU * 2000 + __ITER;
  const checkIn = dateFromOffset(offset);
  const checkOut = dateFromOffset(offset + 4);

  // 요청마다 URL(날짜)이 달라서, k6 기본 태그(url) 그대로 두면 요청 수만큼 고유 시계열이
  // 생겨 "high cardinality" 경고가 뜬다 — 태그를 고정값으로 묶어서 하나의 지표로 집계되게 한다
  const res = http.get(
    `${BASE_URL}/api/v1/stays/search?checkIn=${checkIn}&checkOut=${checkOut}&adults=2&children=0`,
    { tags: { name: 'search' } }
  );
  check(res, { 'status is 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  const avg = data.metrics.http_req_duration?.values.avg || 0;
  const p95 = data.metrics.http_req_duration?.values['p(95)'] || 0;
  const p99 = data.metrics.http_req_duration?.values['p(99)'] || 0;
  const tps = data.metrics.http_reqs?.values.rate || 0;
  const failRate = (data.metrics.http_req_failed?.values.rate || 0) * 100;

  const summary = { avgMs: avg, p95Ms: p95, p99Ms: p99, tps, failRatePercent: failRate };
  const output = {
    stdout:
      `   TPS ${tps.toFixed(1)}/s  평균 ${avg.toFixed(0)}ms  p95 ${p95.toFixed(0)}ms  ` +
      `p99 ${p99.toFixed(0)}ms  실패율 ${failRate.toFixed(1)}%\n`,
  };
  if (__ENV.SUMMARY_FILE) {
    output[__ENV.SUMMARY_FILE] = JSON.stringify(summary);
  }
  return output;
}
