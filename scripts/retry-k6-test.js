// k6로 검색 API에 부하를 걸어 Supplier A 성공률과 응답시간 percentile을 측정한다.
// bash 오케스트레이션(scripts/retry-load-test.sh)이 max-attempts별로 bootstrap을 재기동하고
// mock-supplier를 flaky 모드로 돌린 뒤, 이 스크립트를 한 번씩 호출(k6 run)해서 결과를 모은다.
//
// 단독 실행 예:
//   BASE_URL=http://localhost:8080 VUS=10 REQUESTS=60 TAIL_BASE=300000 \
//     SUMMARY_FILE=/tmp/result.json k6 run scripts/retry-k6-test.js

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TAIL_BASE = parseInt(__ENV.TAIL_BASE || '300000', 10);

export const options = {
  scenarios: {
    search: {
      executor: 'shared-iterations',
      vus: parseInt(__ENV.VUS || '10', 10),
      iterations: parseInt(__ENV.REQUESTS || '60', 10),
      maxDuration: '5m',
    },
  },
  // 기본 요약 통계엔 p99가 없어서 명시적으로 추가해야 handleSummary에서 읽을 수 있다
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

const supplierASuccess = new Rate('supplier_a_success');

function pad(n) {
  return n < 10 ? '0' + n : '' + n;
}

function dateFromOffset(offsetDays) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export default function () {
  // VU 번호와 반복 번호를 조합해서 요청마다 유니크한 날짜를 쓴다 — availabilityCache를
  // 절대 히트하지 않게 해서, 매 요청이 진짜로 Supplier A(재시도 로직 포함)를 타게 만든다.
  // 배율(1000)은 VU당 최대 반복 횟수보다 넉넉히 크면 충분하다 — 너무 크게 잡으면
  // (예: 100000) 오프셋이 누적돼 연도가 5자리로 넘어가서 LocalDate 파싱 자체가
  // 깨지고, 그 요청은 재시도 없이 즉시 에러로 끝나 측정을 완전히 왜곡시킨다.
  const offset = TAIL_BASE + __VU * 1000 + __ITER;
  const checkIn = dateFromOffset(offset);
  const checkOut = dateFromOffset(offset + 4);

  // 요청마다 URL(날짜)이 달라서, k6 기본 태그(url) 그대로 두면 요청 수만큼 고유 시계열이
  // 생겨 "high cardinality" 경고가 뜬다 — 태그를 고정값으로 묶어서 하나의 지표로 집계되게 한다
  const res = http.get(
    `${BASE_URL}/api/v1/stays/search?checkIn=${checkIn}&checkOut=${checkOut}&adults=2&children=0`,
    { tags: { name: 'search' } }
  );

  const ok = check(res, { 'status is 200': (r) => r.status === 200 });

  let supplierASucceeded = false;
  if (ok) {
    try {
      const body = res.json();
      supplierASucceeded = !(body.failedSuppliers || []).includes('SUPPLIER_A');
    } catch (e) {
      supplierASucceeded = false;
    }
  }
  supplierASuccess.add(supplierASucceeded);
}

// 표준 k6 요약 대신, bash가 바로 파싱할 수 있는 압축된 JSON 한 줄을 SUMMARY_FILE에 남긴다.
export function handleSummary(data) {
  const successRate = (data.metrics.supplier_a_success?.values.rate || 0) * 100;
  const p99LatencyMs = data.metrics.http_req_duration?.values['p(99)'] || 0;
  const summary = { successRate, p99LatencyMs };

  const output = {
    stdout: `   성공률 ${successRate.toFixed(1)}%  p99 지연 ${p99LatencyMs.toFixed(0)}ms\n`,
  };
  if (__ENV.SUMMARY_FILE) {
    output[__ENV.SUMMARY_FILE] = JSON.stringify(summary);
  }
  return output;
}
