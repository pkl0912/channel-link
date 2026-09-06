#!/usr/bin/env bash
# 요금/재고 캐시(availabilityCache)의 TTL 설정을 검증하기 위한 부하 테스트.
#
# 실행 전제:
#   1) ./gradlew :mock-supplier:bootRun  (9090)
#   2) ./gradlew :bootstrap:bootRun      (8080)
#
# 왜 "웨이브"로 나눠서 쏘는가:
#   맨 처음 버전은 요청을 한 번에 다 쏘고 끝났는데, 그러면 전체 테스트가 1초도 안 걸려서
#   5초/60초 같은 TTL 차이가 테스트 도중 단 한 번도 실제로 만료되지 않은 채 끝나버린다
#   (테스트 시간 < 가장 짧은 TTL이면 TTL 값과 무관하게 항상 같은 결과가 나옴).
#   그래서 DURATION_SECONDS 동안 WAVE_INTERVAL_SECONDS마다 한 웨이브씩 나눠 쏴서,
#   테스트가 비교 대상 TTL보다 확실히 오래 걸리게 만든다.
#
# 왜 반복(REPEATS)이 필요한가:
#   인기/롱테일 날짜를 $RANDOM으로 뽑기 때문에 1회 실행 결과는 표본 노이즈가 낀다
#   (예: TTL 45s와 60s가 이론상 거의 같은데 1회씩만 돌리면 순서가 뒤집혀 보일 수 있음).
#   REPEATS > 1이면 매 반복마다 완전히 새로운 날짜 풀(블록)을 써서 서로 독립적인
#   시행으로 만들고, 반복이 끝나면 히트율의 평균±표준편차를 계산해 노이즈 폭을 보여준다.
#   (반복 간에는 캐시를 리셋하지 않는 대신, 반복마다 날짜 풀 자체를 다른 구간으로 옮겨서
#   이전 반복에서 캐시된 값과 절대 겹치지 않게 한다 — 그래서 매 반복이 콜드 스타트가 된다)
#
# 사용법:
#   ./scripts/cache-load-test.sh [DURATION_SECONDS] [WAVE_INTERVAL_SECONDS] [REQUESTS_PER_WAVE] [CONCURRENCY] [POPULAR_RANGE_COUNT] [SKEW_PERCENT] [REPEATS]
#   ./scripts/cache-load-test.sh 90 5 20 10 5 80 3
#
# 비교하려는 TTL보다 DURATION_SECONDS가 더 길어야 의미가 있다.
# TTL 자체를 바꾸려면 SupplierSearchPortAdapter.java의 AVAILABILITY_CACHE_TTL을 수정하고
# bootstrap을 재기동한 뒤 이 스크립트를 다시 실행해서 히트율 평균을 비교하면 된다.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
DURATION_SECONDS="${1:-90}"
WAVE_INTERVAL_SECONDS="${2:-5}"
REQUESTS_PER_WAVE="${3:-20}"
CONCURRENCY="${4:-10}"
POPULAR_RANGE_COUNT="${5:-5}"
SKEW_PERCENT="${6:-80}"
REPEATS="${7:-1}"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
TRIALS_FILE="${WORK_DIR}/trials.csv"
: > "$TRIALS_FILE"

now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }

# before/after JSON 두 개를 받아 캐시별 히트율 델타를 한 줄로 출력한다
print_delta() {
  local label="$1" before="$2" after="$3"
  python3 - "$label" "$before" "$after" <<'PY'
import json
import sys

label, before_raw, after_raw = sys.argv[1], sys.argv[2], sys.argv[3]
before = json.loads(before_raw)["availabilityCache"]
after = json.loads(after_raw)["availabilityCache"]

hit_delta = after["hitCount"] - before["hitCount"]
miss_delta = after["missCount"] - before["missCount"]
eviction_delta = after["evictionCount"] - before["evictionCount"]
request_delta = hit_delta + miss_delta
hit_rate = (hit_delta / request_delta * 100) if request_delta else 0.0
# Caffeine의 evictionCount()는 maximumSize 초과뿐 아니라 TTL 만료로 인한 제거도 포함한다.
# 이 테스트는 TTL 만료를 의도적으로 일으키므로 eviction > 0은 정상 — maximumSize가
# 작다는 신호로 보려면 removalListener로 SIZE 원인만 따로 세야 한다(이 스크립트는 안 함).
print(f"{label}  요청 {request_delta:>4}건  히트 {hit_delta:>4}  미스 {miss_delta:>4}  히트율 {hit_rate:5.1f}%  eviction(TTL 만료 포함) {eviction_delta}")
PY
}

# before/after JSON을 받아 "hit_delta,miss_delta,eviction_delta"를 CSV 한 줄로 파일에 남긴다 (반복 집계용)
record_trial() {
  local before="$1" after="$2"
  python3 - "$before" "$after" <<'PY' >> "$TRIALS_FILE"
import json
import sys

before = json.loads(sys.argv[1])["availabilityCache"]
after = json.loads(sys.argv[2])["availabilityCache"]
hit_delta = after["hitCount"] - before["hitCount"]
miss_delta = after["missCount"] - before["missCount"]
eviction_delta = after["evictionCount"] - before["evictionCount"]
print(f"{hit_delta},{miss_delta},{eviction_delta}")
PY
}

echo "==> 사전 점검: bootstrap(8080) 응답 확인"
if ! curl -sf "${BASE_URL}/internal/cache-stats" > /dev/null; then
  echo "bootstrap(8080)에 연결할 수 없습니다. ./gradlew :bootstrap:bootRun 을 먼저 실행하세요." >&2
  exit 1
fi

num_waves=$(( DURATION_SECONDS / WAVE_INTERVAL_SECONDS ))
if (( num_waves < 1 )); then
  num_waves=1
fi

echo "==> 설정: 총 ${DURATION_SECONDS}초 동안 ${WAVE_INTERVAL_SECONDS}초마다 웨이브(${num_waves}회), 웨이브당 요청 ${REQUESTS_PER_WAVE}건, ${REPEATS}회 반복"
echo "    (비교 중인 TTL이 ${DURATION_SECONDS}초보다 길면 이 테스트로는 만료를 관찰 못 함)"

# 반복마다 날짜 풀을 완전히 다른 구간으로 옮겨서, 이전 반복의 캐시 상태와 절대 안 겹치게 한다
POPULAR_BLOCK_SIZE=$(( POPULAR_RANGE_COUNT * 5 + 10 ))
tail_counter=100000

run_trial() {
  local repeat_index="$1"
  local block_base=$(( repeat_index * POPULAR_BLOCK_SIZE + 1 ))

  local popular_ranges=()
  for ((i = 0; i < POPULAR_RANGE_COUNT; i++)); do
    local offset=$(( block_base + i * 5 ))
    local check_in check_out
    check_in=$(date -v"+${offset}d" +%Y-%m-%d 2>/dev/null || date -d "+${offset} days" +%Y-%m-%d)
    check_out=$(date -v"+$((offset + 4))d" +%Y-%m-%d 2>/dev/null || date -d "+$((offset + 4)) days" +%Y-%m-%d)
    popular_ranges+=("${check_in},${check_out}")
  done

  generate_wave_urls() {
    : > "${WORK_DIR}/urls.txt"
    for ((i = 0; i < REQUESTS_PER_WAVE; i++)); do
      local roll range check_in check_out
      roll=$((RANDOM % 100))
      if ((roll < SKEW_PERCENT)); then
        local idx=$((RANDOM % POPULAR_RANGE_COUNT))
        range="${popular_ranges[$idx]}"
      else
        tail_counter=$((tail_counter + 1))
        check_in=$(date -v"+${tail_counter}d" +%Y-%m-%d 2>/dev/null || date -d "+${tail_counter} days" +%Y-%m-%d)
        check_out=$(date -v"+$((tail_counter + 4))d" +%Y-%m-%d 2>/dev/null || date -d "+$((tail_counter + 4)) days" +%Y-%m-%d)
        range="${check_in},${check_out}"
      fi
      check_in="${range%,*}"
      check_out="${range#*,}"
      echo "${BASE_URL}/api/v1/stays/search?checkIn=${check_in}&checkOut=${check_out}&adults=2&children=0" >> "${WORK_DIR}/urls.txt"
    done
  }

  local trial_start_stats trial_start_ms
  trial_start_stats=$(curl -s "${BASE_URL}/internal/cache-stats")
  trial_start_ms=$(now_ms)

  for ((wave = 1; wave <= num_waves; wave++)); do
    local wave_start_ms before after elapsed_s wave_end_ms spent_ms sleep_ms
    wave_start_ms=$(now_ms)
    before=$(curl -s "${BASE_URL}/internal/cache-stats")

    generate_wave_urls
    xargs -P "${CONCURRENCY}" -I{} curl -s -o /dev/null {} < "${WORK_DIR}/urls.txt"

    after=$(curl -s "${BASE_URL}/internal/cache-stats")
    elapsed_s=$(( (wave_start_ms - trial_start_ms) / 1000 ))
    print_delta "    [t=${elapsed_s}s 웨이브 ${wave}/${num_waves}]" "$before" "$after"

    wave_end_ms=$(now_ms)
    spent_ms=$(( wave_end_ms - wave_start_ms ))
    sleep_ms=$(( WAVE_INTERVAL_SECONDS * 1000 - spent_ms ))
    if (( sleep_ms > 0 )); then
      sleep "$(python3 -c "print(${sleep_ms} / 1000)")"
    fi
  done

  local trial_end_stats
  trial_end_stats=$(curl -s "${BASE_URL}/internal/cache-stats")
  print_delta "  [반복 $((repeat_index + 1))/${REPEATS} 요약]" "$trial_start_stats" "$trial_end_stats"
  record_trial "$trial_start_stats" "$trial_end_stats"
}

for ((r = 0; r < REPEATS; r++)); do
  echo "==> 반복 $((r + 1))/${REPEATS} 시작"
  run_trial "$r"
  echo
done

echo "==> ${REPEATS}회 반복 종합 (평균 ± 표준편차)"
python3 - "$TRIALS_FILE" "$REPEATS" <<'PY'
import csv
import statistics
import sys

trials_file, repeats = sys.argv[1], int(sys.argv[2])
hit_rates = []
evictions = []
with open(trials_file, newline="") as f:
    for hit, miss, eviction in csv.reader(f):
        hit, miss, eviction = int(hit), int(miss), int(eviction)
        total = hit + miss
        hit_rates.append((hit / total * 100) if total else 0.0)
        evictions.append(eviction)

mean_hit_rate = statistics.mean(hit_rates)
stdev_hit_rate = statistics.stdev(hit_rates) if len(hit_rates) > 1 else 0.0
print(f"  히트율: {mean_hit_rate:.1f}% ± {stdev_hit_rate:.1f}%p  (표본 {len(hit_rates)}개: "
      + ", ".join(f"{h:.1f}%" for h in hit_rates) + ")")
print(f"  eviction(TTL 만료 포함) 평균: {statistics.mean(evictions):.1f}")
PY
