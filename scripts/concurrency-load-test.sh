#!/usr/bin/env bash
# 동시 요청 수를 늘려가며 p95/p99 지연시간, TPS, CPU 사용률, WebClient 커넥션 풀
# 사용량이 어떻게 변하는지 확인하는 부하 테스트.
#
# 실행 전제:
#   1) ./gradlew :mock-supplier:bootRun (9090)
#   2) ./gradlew :bootstrap:bootRun (8080)
#   둘 다 이미 떠 있어야 한다 (동시성 자체는 코드가 아니라 부하 크기만 바뀌는 실험이라
#   bootstrap을 재기동할 필요는 없다). bootstrap은 actuator가 추가돼 있어야 하고
#   (spring-boot-starter-actuator), management.endpoints.web.exposure.include에
#   health,metrics가 노출돼 있어야 한다 — 최신 코드로 재기동했다면 이미 반영돼 있다.
#
# 무엇을 측정하는가:
#   mock-supplier에 A/B 각각 고정된 인위적 지연(기본 100ms/150ms — 실제 외부 API를
#   흉내내는 값)을 걸어두고, VUS_LIST의 각 동시성 값으로 DURATION 동안 지속 부하를 걸며
#     - p95/p99 지연시간, TPS: k6가 측정 (concurrency-k6-test.js)
#     - CPU 사용률(process.cpu.usage), WebClient 커넥션 풀 활성 연결 수
#       (reactor.netty.connection.provider.active/max.connections): /actuator/metrics를
#       1초 간격으로 폴링해서 평균/최댓값을 기록
#   을 동시성 수준별로 비교한다. 동시성이 늘어날 때 지연시간이 어느 지점부터 튀는지,
#   커넥션 풀이 한계(max.connections, 기본 500)에 가까워지는지를 보면 병목 지점을 알 수 있다.
#
# RAMP_SECONDS(선택, 기본 0 = constant-vus)를 주면 0명에서 각 VUS까지 그 시간에 걸쳐
# 서서히 접속시킨다 — constant-vus는 테스트 시작 순간 VUS명이 한꺼번에 접속을 시도해서
# OS 커널의 listen backlog를 넘기면 "connection reset by peer"가 나는데(로컬 macOS는
# kern.ipc.somaxconn 기본값이 128로 낮음), 점진적으로 늘리면 실제 트래픽에 더 가깝고
# 이 버스트 자체가 생기지 않는다.
#
# 사용법:
#   ./scripts/concurrency-load-test.sh [DELAY_A_MS] [DELAY_B_MS] [DURATION] ["VUS_LIST"] [RAMP_SECONDS]
#   ./scripts/concurrency-load-test.sh 100 150 15s "10 50 100 200" 10

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K6_SCRIPT="${SCRIPT_DIR}/concurrency-k6-test.js"

BASE_URL="${BASE_URL:-http://localhost:8080}"
MOCK_URL="${MOCK_URL:-http://localhost:9090}"
DELAY_A_MS="${1:-100}"
DELAY_B_MS="${2:-150}"
DURATION="${3:-15s}"
VUS_LIST="${4:-10 50 100 200}"
RAMP_SECONDS="${5:-0}"

WORK_DIR="$(mktemp -d)"
cleanup() {
  curl -s -X POST "${MOCK_URL}/control/a/delay-ms?value=0" > /dev/null 2>&1 || true
  curl -s -X POST "${MOCK_URL}/control/b/delay-ms?value=0" > /dev/null 2>&1 || true
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

echo "==> 사전 점검: mock-supplier(9090), bootstrap(8080)의 actuator, k6 확인"
if ! curl -sf "${MOCK_URL}/a/v1/hotels" > /dev/null; then
  echo "mock-supplier(9090)에 연결할 수 없습니다. ./gradlew :mock-supplier:bootRun 을 먼저 실행하세요." >&2
  exit 1
fi
if ! curl -sf "${BASE_URL}/actuator/metrics/process.cpu.usage" > /dev/null; then
  echo "bootstrap(8080)의 /actuator/metrics에 연결할 수 없습니다. actuator가 포함된 최신 코드로" >&2
  echo "./gradlew :bootstrap:bootRun 을 (재)실행하세요." >&2
  exit 1
fi
if ! command -v k6 > /dev/null; then
  echo "k6가 설치돼 있지 않습니다. 'brew install k6'로 설치하세요." >&2
  exit 1
fi

echo "==> mock-supplier: A는 ${DELAY_A_MS}ms, B는 ${DELAY_B_MS}ms 고정 지연 설정 (실제 외부 API 흉내)"
curl -s -X POST "${MOCK_URL}/control/a/mode?value=normal" > /dev/null
curl -s -X POST "${MOCK_URL}/control/b/mode?value=normal" > /dev/null
curl -s -X POST "${MOCK_URL}/control/a/delay-ms?value=${DELAY_A_MS}" > /dev/null
curl -s -X POST "${MOCK_URL}/control/b/delay-ms?value=${DELAY_B_MS}" > /dev/null

metric_value() {
  # actuator 응답에서 measurements[0].value만 뽑는다. 실패하면 빈 값.
  python3 -c "
import json, sys
try:
    print(json.load(sys.stdin)['measurements'][0]['value'])
except Exception:
    pass
" 2>/dev/null
}

sample_loop() {
  local out_file="$1"
  while true; do
    cpu=$(curl -s "${BASE_URL}/actuator/metrics/process.cpu.usage" | metric_value)
    active=$(curl -s "${BASE_URL}/actuator/metrics/reactor.netty.connection.provider.active.connections" | metric_value)
    echo "${cpu:-0},${active:-0}" >> "$out_file"
    sleep 1
  done
}

max_conn=$(curl -s "${BASE_URL}/actuator/metrics/reactor.netty.connection.provider.max.connections" | metric_value)
echo "==> WebClient 커넥션 풀 최대 크기: ${max_conn:-알 수 없음}"

results_file="${WORK_DIR}/summary.csv"
: > "$results_file"
tail_base=$(( 1000 + ($(date +%s) % 4000) ))

for vus in $VUS_LIST; do
  if [ "$RAMP_SECONDS" -gt 0 ]; then
    echo "==> 동시성 VUs=${vus} (${RAMP_SECONDS}초에 걸쳐 점진 증가), 이후 ${DURATION} 동안 유지"
  else
    echo "==> 동시성 VUs=${vus}, ${DURATION} 동안 지속 부하 (순간 접속)"
  fi
  tail_base=$(( tail_base + vus * 2000 + 50000 ))

  samples_file="${WORK_DIR}/samples_${vus}.csv"
  : > "$samples_file"
  sample_loop "$samples_file" &
  sampler_pid=$!

  k6_summary="${WORK_DIR}/k6_${vus}.json"
  BASE_URL="$BASE_URL" VUS="$vus" DURATION="$DURATION" TAIL_BASE="$tail_base" \
    RAMP_SECONDS="$RAMP_SECONDS" SUMMARY_FILE="$k6_summary" \
    k6 run --quiet "$K6_SCRIPT"

  kill "$sampler_pid" 2>/dev/null || true
  wait "$sampler_pid" 2>/dev/null || true

  python3 - "$k6_summary" "$samples_file" "$vus" <<'PY' >> "$results_file"
import csv
import json
import statistics
import sys

k6_file, samples_file, vus = sys.argv[1], sys.argv[2], sys.argv[3]
with open(k6_file) as f:
    k6 = json.load(f)

cpus, actives = [], []
with open(samples_file, newline="") as f:
    for cpu, active in csv.reader(f):
        try:
            cpus.append(float(cpu))
            actives.append(float(active))
        except ValueError:
            continue

cpu_avg = statistics.mean(cpus) * 100 if cpus else 0.0
cpu_max = max(cpus) * 100 if cpus else 0.0
conn_avg = statistics.mean(actives) if actives else 0.0
conn_max = max(actives) if actives else 0.0

print(f"{vus},{k6['tps']:.1f},{k6['avgMs']:.0f},{k6['p95Ms']:.0f},{k6['p99Ms']:.0f},"
      f"{k6['failRatePercent']:.1f},{cpu_avg:.1f},{cpu_max:.1f},{conn_avg:.1f},{conn_max:.1f}")
PY
done

echo
echo "==> 최종 비교"
python3 - "$results_file" <<'PY'
import csv
import sys

with open(sys.argv[1], newline="") as f:
    rows = list(csv.reader(f))

header = ["VUs", "TPS", "평균(ms)", "p95(ms)", "p99(ms)", "실패율(%)", "CPU평균(%)", "CPU최대(%)", "커넥션평균", "커넥션최대"]
widths = [6, 8, 9, 8, 8, 8, 10, 10, 9, 9]
print(" | ".join(h.rjust(w) for h, w in zip(header, widths)))
for row in rows:
    print(" | ".join(v.rjust(w) for v, w in zip(row, widths)))
PY
