#!/usr/bin/env bash
# Supplier A의 재시도 횟수(resilience4j max-attempts) 설정을 검증하기 위한 부하 테스트.
# 실제 요청 발사·응답시간 percentile 계산은 k6(scripts/retry-k6-test.js)에 맡기고,
# 이 스크립트는 "max-attempts별로 bootstrap을 재기동하고 mock-supplier를 flaky로 돌린 뒤
# k6를 실행해서 결과를 모으는" 오케스트레이션만 담당한다 — k6는 요청을 쏘고 측정할 뿐
# 테스트 대상 서버(bootstrap)를 재기동하거나 mock-supplier 모드를 바꾸는 일은 못 하므로
# 이 부분은 여전히 셸에서 처리해야 한다.
#
# 실행 전제:
#   1) ./gradlew :mock-supplier:bootRun (9090) 이 이미 떠 있어야 한다.
#   2) k6가 설치돼 있어야 한다 (brew install k6).
#   3) bootstrap(8080)은 이 스크립트가 max-attempts 값마다 직접 껐다 켰다 한다
#      (resilience4j 설정을 --args로 오버라이드해서 기동하기 위함 — yml을 매번 고칠 필요 없음).
#      이 스크립트를 실행하기 전에 8080을 쓰고 있던 bootstrap이 있었다면 종료된다.
#
# 무엇을 측정하는가:
#   Supplier A를 "flaky" 모드로 만들어 요청마다 독립적으로 FLAKY_RATE_PERCENT% 확률로
#   실패하게 만든 뒤, MAX_ATTEMPTS_LIST의 각 값으로 bootstrap을 재기동해서 검색을 반복 실행하고
#     - 성공률: 검색 결과의 failedSuppliers에 SUPPLIER_A가 없는 비율
#     - p99 지연시간: 검색 API 응답 시간의 99번째 백분위
#   을 측정한다. 서킷브레이커가 이 실험에 끼어들지 않도록 supplierA의 서킷브레이커
#   sliding-window-size를 아주 크게 오버라이드해서 사실상 비활성화한다(재시도 자체의
#   효과만 보기 위함 — 서킷브레이커가 열리면 그 이후 요청은 재시도를 시도하지도 않고
#   즉시 실패해서 max-attempts와 무관한 결과가 나옴).
#
#   k6 스크립트(retry-k6-test.js)는 요청마다 TAIL_BASE + __VU*1000 + __ITER로 날짜를
#   만들어 availabilityCache를 피한다. 반복(REPEATS)마다 TAIL_BASE를 옮기는 폭이
#   VU 범위(최대 CONCURRENCY*1000)보다 작으면 반복끼리 날짜가 겹쳐 캐시를 타버리고,
#   캐시 히트는 재시도 로직 자체를 안 태우므로 측정이 왜곡된다 — 그래서 증분 폭을
#   CONCURRENCY*1000보다 확실히 크게 잡는다. (배율을 너무 크게 잡으면 오프셋이 누적돼
#   연도가 5자리를 넘어서 LocalDate 파싱 자체가 깨지는 별도 문제가 생기니 이 정도로 충분히 작게 유지한다)
#
# 사용법:
#   ./scripts/retry-load-test.sh [FLAKY_RATE_PERCENT] [REQUESTS] [CONCURRENCY] [REPEATS] [MAX_ATTEMPTS_LIST]
#   ./scripts/retry-load-test.sh 30 60 10 3 "1 2 3 5"

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K6_SCRIPT="${SCRIPT_DIR}/retry-k6-test.js"

BASE_URL="${BASE_URL:-http://localhost:8080}"
MOCK_URL="${MOCK_URL:-http://localhost:9090}"
FLAKY_RATE="${1:-30}"
REQUESTS="${2:-60}"
CONCURRENCY="${3:-10}"
REPEATS="${4:-3}"
MAX_ATTEMPTS_LIST="${5:-1 2 3 5}"

WORK_DIR="$(mktemp -d)"
cleanup() {
  lsof -ti:8080 | xargs -r kill 2>/dev/null || true
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

echo "==> 사전 점검: mock-supplier(9090), k6 확인"
if ! curl -sf "${MOCK_URL}/a/v1/hotels" > /dev/null; then
  echo "mock-supplier(9090)에 연결할 수 없습니다. ./gradlew :mock-supplier:bootRun 을 먼저 실행하세요." >&2
  exit 1
fi
if ! command -v k6 > /dev/null; then
  echo "k6가 설치돼 있지 않습니다. 'brew install k6'로 설치하세요." >&2
  exit 1
fi
if ! curl -sf -X POST "${MOCK_URL}/control/a/flaky-rate?value=0" > /dev/null; then
  echo "mock-supplier에 flaky-rate 컨트롤 엔드포인트가 없습니다 — 최신 코드로 mock-supplier를 재기동하세요." >&2
  exit 1
fi

RESULTS_FILE="${WORK_DIR}/summary.csv"
: > "$RESULTS_FILE"
# 스크립트를 다시 실행해도 이전 실행분과 안 겹치도록 현재 시각을 섞어서 시작점을 잡는다
# (그래도 4자리 연도 범위 안에 확실히 들어오도록 5000일 안쪽으로 제한)
tail_base=$(( 1000 + ($(date +%s) % 4000) ))
# k6가 쓰는 오프셋 범위(CONCURRENCY*1000 + REQUESTS)보다 확실히 크게 증분해서
# 반복끼리, max-attempts 값끼리 날짜가 절대 안 겹치게 한다
tail_increment=$(( CONCURRENCY * 1000 + REQUESTS + 2000 ))

for max_attempts in $MAX_ATTEMPTS_LIST; do
  echo "==> max-attempts=${max_attempts} 로 bootstrap 재기동 (서킷브레이커는 이 실험 동안 사실상 비활성화)"
  lsof -ti:8080 | xargs -r kill 2>/dev/null || true
  sleep 1

  bootstrap_log="${WORK_DIR}/bootstrap_${max_attempts}.log"
  ./gradlew :bootstrap:bootRun --console=plain \
    --args="--resilience4j.retry.instances.supplierA.max-attempts=${max_attempts} --resilience4j.circuitbreaker.instances.supplierA.sliding-window-size=1000000" \
    > "$bootstrap_log" 2>&1 &

  for i in $(seq 1 40); do
    grep -qE "Started ChannelLinkApplication|APPLICATION FAILED TO START" "$bootstrap_log" 2>/dev/null && break
    sleep 1
  done
  if ! grep -q "Started ChannelLinkApplication" "$bootstrap_log"; then
    echo "bootstrap 기동 실패 (max-attempts=${max_attempts}). 로그:" >&2
    cat "$bootstrap_log" >&2
    exit 1
  fi

  curl -s -X POST "${MOCK_URL}/control/a/flaky-rate?value=${FLAKY_RATE}" > /dev/null
  curl -s -X POST "${MOCK_URL}/control/a/mode?value=flaky" > /dev/null
  curl -s -X POST "${MOCK_URL}/control/b/mode?value=normal" > /dev/null

  trial_results_file="${WORK_DIR}/trials_${max_attempts}.csv"
  : > "$trial_results_file"

  for ((r = 0; r < REPEATS; r++)); do
    echo "   반복 $((r + 1))/${REPEATS} — 요청 ${REQUESTS}건, VUs ${CONCURRENCY}, flaky ${FLAKY_RATE}%"

    # 트라이얼마다 날짜 블록을 다른 구간으로 옮겨서 이전 트라이얼과 겹치지 않게 한다
    tail_base=$(( tail_base + tail_increment ))
    summary_file="${WORK_DIR}/k6_${max_attempts}_${r}.json"

    BASE_URL="$BASE_URL" VUS="$CONCURRENCY" REQUESTS="$REQUESTS" TAIL_BASE="$tail_base" \
      SUMMARY_FILE="$summary_file" \
      k6 run --quiet "$K6_SCRIPT"

    python3 - "$summary_file" <<'PY' >> "$trial_results_file"
import json
import sys

with open(sys.argv[1]) as f:
    data = json.load(f)
print(f"{data['successRate']},{data['p99LatencyMs']:.0f}")
PY
  done

  echo "   -> $(cat "$trial_results_file" | tr '\n' ' ')"

  python3 - "$trial_results_file" "$max_attempts" <<'PY' >> "$RESULTS_FILE"
import csv
import statistics
import sys

trial_file, max_attempts = sys.argv[1], sys.argv[2]
success_rates, p99s = [], []
with open(trial_file, newline="") as f:
    for sr, p99 in csv.reader(f):
        success_rates.append(float(sr))
        p99s.append(float(p99))

sr_mean = statistics.mean(success_rates)
sr_std = statistics.stdev(success_rates) if len(success_rates) > 1 else 0.0
p99_mean = statistics.mean(p99s)
p99_std = statistics.stdev(p99s) if len(p99s) > 1 else 0.0
print(f"{max_attempts},{sr_mean:.1f},{sr_std:.1f},{p99_mean:.0f},{p99_std:.0f}")
PY

  lsof -ti:8080 | xargs -r kill 2>/dev/null || true
  sleep 1
done

echo
echo "==> 최종 비교 (flaky ${FLAKY_RATE}%, 반복 ${REPEATS}회 평균±표준편차)"
python3 - "$RESULTS_FILE" <<'PY'
import csv
import sys

with open(sys.argv[1], newline="") as f:
    rows = list(csv.reader(f))

print(f"{'max-attempts':>12} | {'성공률':>18} | {'p99 지연(ms)':>18}")
for max_attempts, sr_mean, sr_std, p99_mean, p99_std in rows:
    print(f"{max_attempts:>12} | {float(sr_mean):>6.1f}% ± {float(sr_std):>4.1f}%p  | {float(p99_mean):>7.0f} ± {float(p99_std):>5.0f}")
PY
