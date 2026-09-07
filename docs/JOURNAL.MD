# JOURNAL

## Day 1 - 프로젝트 세팅 & 도메인 모델 설계

### 수행 내용

- Gradle 멀티모듈 프로젝트 세팅
  - Groovy DSL / Java 21 / Spring Boot 3.4
  - `domain` / `application` / `adapter-in-web` / `adapter-out-supplier` / `adapter-out-persistence` / `bootstrap` / `mock-supplier` 총 7개 모듈로 분리
- Mock Supplier 분리
  - 본체와 같은 포트에서 자기 자신을 호출하는 문제를 피하기 위해 별도 프로세스(9090)로 구성
- 도메인 모델 설계
  - Supplier A/B의 서로 다른 응답을 통일할 표준 모델: `Hotel`, `RoomType`, `DailyInventory`, `StayOffer`
  - DB에 영속할 매핑 모델: `HotelMapping`, `RoomTypeMapping`
  - `HotelMappingRepository` Port와 Adapter 구현

### 의사결정

#### 1. 멀티모듈 vs 단일모듈

- **결정:** 멀티모듈
- **대안:** 패키지로만 레이어를 나누는 단일 모듈
- **핵심 이유:** 빌드 자체로 계층 경계를 강제
- **구현:** `domain` 모듈에 Spring·JPA·WebClient 의존성을 넣지 않음
  - 외부 연동 코드가 도메인 계층으로 새어 들어오면 컴파일 단계에서 발견할 수 있음
- **트레이드오프:** 모듈별 `build.gradle` 관리 및 초기 세팅 시간 증가

#### 2. 헥사고날 아키텍처

- **결정:** 헥사고날 아키텍처(Port & Adapter)
- **대안:** Controller-Service-Repository 3계층
- **핵심 이유:** Supplier별 요청/응답 형식을 도메인 계층과 분리
- **효과:** 신규 Supplier 추가 시 기존 로직을 수정하지 않고 Adapter를 추가할 수 있음

#### 3. 표준 모델과 영속 대상 분리

- **결정:** `HotelMapping` / `RoomTypeMapping`만 영속
- **대안:** 공급사 응답을 그대로 캐싱하는 별도 테이블(RoomType 캐시) 추가
- **영속하지 않은 모델:** `Hotel` / `RoomType` / `DailyInventory` / `StayOffer`
- **핵심 이유:**
  - `roomTypeName` / `maxOccupancy` 등의 정보는 재고·요금 조회 응답에도 매번 포함됨
  - 별도 캐시 테이블에 저장해도 결국 공급사에서 다시 받아오는 값의 사본이 됨
  - 요금과 재고의 원본은 외부 공급사에 있으므로 매핑 외 데이터는 저장할 필요가 없다고 판단
- **트레이드오프:** 조회 성능 최적화 기회를 포기
  - 필요해지면 캐시나 영속 모델을 선택적으로 추가 가능

#### 4. `breakfastIncluded` / `currency`의 위치

- **결정:** `StayOffer`
- **대안:** 방의 정적 속성인 `RoomType`에 배치
- **핵심 이유:** 조식 포함 여부와 통화는 객실 자체보다 요금에 종속된 조건
  - 같은 객실이라도 공급사에 따라 조식 포함 여부가 다를 수 있음

### 참고 자료

- [Hexagonal Architecture with Java and Spring](https://reflectoring.io/spring-hexagonal/)


## Day 2 - Supplier 연동 어댑터 (레지스트리 패턴)

### 수행 내용

- `domain`에 `SupplierClient` Port 추가
  - `fetchHotelCatalog()`
  - `searchAvailability(hotelCodes, criteria)`
  - Supplier 간 공통 규약인 `SearchCriteria` 작성
- `SupplierAClient` / `SupplierBClient` 구현
  - WebClient로 mock-supplier 호출
  - 각 Supplier의 원본 응답을 표준 모델(`Hotel` / `RoomType` / `DailyInventory` / `StayOffer`)로 정규화
- `SupplierClientRegistry` 구현
  - Map 기반 레지스트리 패턴 적용
- 내부 식별자 생성 방식 변경
  - `UUID.randomUUID()`(v4) → UUIDv7
  - `adapter-out-persistence`에 직접 구현

### 의사결정

#### 1. Supplier 실패를 하나의 예외로 통일

- **결정:** `SupplierCallException`으로 통일
- **대안:** 각 Adapter가 Supplier별 방식으로 예외를 처리
- **핵심 이유:** Supplier마다 실패를 표현하는 방식이 달라도 상위 계층에서는 동일한 방식으로 처리할 수 있어야 함
- **Supplier A:** HTTP 4xx / 5xx
- **Supplier B:** HTTP 200이더라도 `resultCode != "0000"`이면 실패
  - B의 응답 코드는 Adapter에서 확인한 뒤 `SupplierCallException`을 발생시킴

#### 2. 신규 Supplier 추가를 대비해 레지스트리 패턴 사용

- **결정:** `SupplierClientRegistry`
- **대안:** `SupplierCode`별 `if/else` 또는 `switch` 분기
- **핵심 이유:** 신규 Supplier 추가 시 기존 분기 코드를 수정하지 않기 위해
- **효과:**
  - 기존 방식: Supplier가 추가될 때마다 분기 코드 수정
  - Registry 방식: Supplier Adapter를 `@Component`로 추가하고 Registry에 등록

#### 3. `EnvelopeB<T>` 역직렬화에 `ParameterizedTypeReference` 사용

- **문제:** Supplier B 응답은 `{resultCode, resultMessage, data}` 형태의 제네릭 봉투 구조
- `.bodyToMono(EnvelopeB.class)`처럼 `Class` 객체를 전달하면 제네릭 타입 소거로 인해 `data`가 실제 타입이 아닌 `LinkedHashMap`으로 역직렬화됨
- **해결:** `ParameterizedTypeReference<EnvelopeB<PropertiesData>>() {}` 사용
  - 익명 서브클래스로 제네릭 타입 정보를 런타임까지 유지
- **구현상 제약:** 호출 시점에 타입 파라미터가 고정돼야 하므로 `PropertiesData`용 / `SearchData`용 메서드를 각각 분리

#### 4. 내부 식별자를 UUIDv4 대신 UUIDv7로 변경

- **결정:**
  - UUIDv4 → UUIDv7
  - 직접 구현
  - `adapter-out-persistence`에 배치
- **대안:**
  - UUIDv4 유지
  - UUIDv7로 변경
  - 생성 로직을 `domain`에 둘지 `adapter-out-persistence`에 둘지
  - 직접 구현할지 `uuid-creator` 같은 라이브러리를 사용할지

**UUIDv4 → UUIDv7**

- UUIDv4는 완전한 무작위 값이라 B-tree 인덱스에 무작위 위치로 삽입됨
- UUIDv7은 앞 48비트가 밀리초 타임스탬프라 시간순 삽입에 유리함
- 데이터 건수가 현재는 작아 체감 차이는 없지만, 데이터 증가에 따른 인덱스 지역성을 고려해 UUIDv7을 선택

**`domain` → `adapter-out-persistence`**

- UUIDv7을 선택한 근거인 B-tree 인덱스 지역성은 도메인 규칙이 아니라 저장소의 물리적 특성
- 따라서 생성 로직을 persistence Adapter에 배치

**직접 구현**

- 약 20줄 수준의 코드라 외부 라이브러리를 추가하기보다 직접 구현하고 검증하는 쪽을 선택

**트레이드오프**

- 같은 밀리초 안에서의 엄격한 단조 증가(monotonic)는 보장하지 않음
- `uuid-creator`의 `getTimeOrderedEpoch()`는 카운터를 사용해 이를 보장하지만, 현재 매핑 생성 빈도에서는 불필요하다고 판단

### 참고 자료

- [Spring `ParameterizedTypeReference` Javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/ParameterizedTypeReference.html)
- [RFC 9562 - UUID Version 7](https://www.rfc-editor.org/rfc/rfc9562.html#name-uuid-version-7)


## Day 3 - 통합 검색 API 완성 + 병렬 호출 방식 재검토 + 네이밍 정리

### 수행 내용

- 통합 검색 API 구현
  - `SearchStayPort` / `RefreshHotelCatalogPort` (`domain`)
  - `SearchStayService` / `RefreshHotelCatalogService` (`application`)
  - `StayController` (`adapter-in-web`)
- WebClient의 Reactor 특성을 활용한 Supplier 병렬 호출 방식으로 변경

### 의사결정

#### 1. WebClient의 Reactor 체인을 그대로 살리는 방식으로 변경

- **결정:** WebClient의 `Mono` / `Flux`를 `adapter-out-supplier` 내부에서 끝까지 유지
- **대안:**
  - `SupplierPort.searchAvailability()`를 blocking 방식으로 호출
  - 가상 스레드 executor + `CompletableFuture`로 병렬화
- **핵심 이유:** WebClient는 이미 Reactor Netty 기반의 non-blocking 클라이언트이므로, 이를 `.block()`으로 동기화한 뒤 다시 가상 스레드로 병렬화할 필요가 없음

**구조**

- `domain`에 `SupplierSearchPort` 추가
  - 외부에는 평범한 동기 인터페이스 `searchAll(...) : List<SupplierSearchOutcome>`만 노출
- `SupplierSearchPortAdapter`
  - `Flux.fromIterable(...).flatMap(...)`으로 Supplier를 동시에 호출
  - `.block()`은 Adapter의 최종 반환 지점에서 한 번만 호출
- `SupplierAClient` / `SupplierBClient`
  - 기존 blocking 계약인 `SupplierPort`와 별도로 패키지 프라이빗 `ReactiveSupplierSearch` 구현
  - `ReactiveSupplierSearch`는 `Mono`를 반환
  - Spring이 `List<ReactiveSupplierSearch>`를 자동 수집하고 Adapter에서 사용

**결과**

- 호출자는 `SupplierSearchPort` 하나만 동기 방식으로 사용
- `Mono` / `Flux` / Reactor는 `adapter-out-supplier` 내부에만 존재
- `domain` / `application`에는 Reactor import가 0건

#### 2. `application` / `adapter-out-supplier`에 Lombok 도입

- **결정:** Lombok 도입
  - `application/build.gradle`
  - `adapter-out-supplier/build.gradle`
- **대안:** 외부 의존성 0을 유지하고 생성자를 직접 작성
- **핵심 이유:** Lombok은 Spring·JPA·Reactor와 달리 컴파일 타임에만 관여하는 코드 생성기
  - 생성된 `.class`는 직접 작성한 코드와 동일
  - `compileOnly`로 설정하면 런타임에 Lombok 자체가 필요하지 않음
- 따라서 기존의 "외부 기술과 런타임에 결합하지 않는다"는 원칙은 유지하면서 보일러플레이트를 줄이는 방향으로 완화

**트레이드오프**

- `@AllArgsConstructor`는 단순 필드 대입만 생성하므로 기존 `List.copyOf()` 방어적 복사를 유지할 수 없음
- 하지만 해당 서비스의 생성자는 `UseCaseConfig` 한 곳에서만 호출되며, Spring이 매번 새 리스트를 전달하므로 리스트가 외부에서 변경되는 시나리오는 없다고 판단

**적용 범위**

- `domain`의 모델·Port에는 Lombok을 도입하지 않음
- `record`만으로 생성자·`equals`·`toString`이 제공되므로 추가 필요성이 없었음

#### 3. 숙소 목록 갱신(refresh) 시점

- **결정:** 애플리케이션 기동 시 1회
  - `bootstrap`의 `CommandLineRunner catalogWarmup`
- **대안:**
  - 기동 시 1회
  - 주기적 스케줄
  - 별도 관리용 트리거

**핵심 이유**

- 숙소 목록은 "자주 안 바뀌는 정적 콘텐츠"
- 재고·요금은 매번 변경될 수 있는 동적 데이터
- 따라서 검색 때마다 숙소 목록을 다시 조회하지 않고 애플리케이션 생애주기 동안 한 번만 갱신

**왜 필수인가?**

- `SearchStayService.search()`의 첫 단계가 매핑 조회
- `refresh()`가 한 번도 실행되지 않으면 매핑 테이블이 비어 있음
- 결과적으로 검색 결과가 항상 빈 배열이 됨

**트레이드오프**

- 과제 스코프상 주기적 스케줄러나 별도 관리자 트리거는 구현하지 않음


## Day 4 - 테스트 코드, 캐싱, 재시도/서킷브레이커, 성능·병렬성 검증

### 수행 내용

- 단위 테스트 추가
  - `SearchStayServiceTest`
  - `SearchCriteriaTest`
  - `MoneyTest`
- 캐싱 2종 도입
  - 숙소 매핑 목록: `hotelMappingsBySupplier`
  - 요금/재고: `availabilityCache`
- resilience4j 기반 Retry / CircuitBreaker 도입
- k6 기반 부하 테스트
  - 캐시 TTL
  - 재시도 횟수
  - 동시성 확장성 검증

### 의사결정

#### 1. 테스트 코드

- **결정:** 핵심 비즈니스 로직인 `SearchStayService`부터 테스트
- **대안:** WebClient 파싱 등 Adapter 계층부터 테스트
- **방법:** Mockito로 `SupplierPort` / `HotelMappingPort` / `SupplierSearchPort`를 모킹한 순수 단위 테스트

**검증 항목**

- 모든 날짜에 재고가 있으면 정상 반환
- 기간 중 하루라도 재고가 0이면 `availableRooms=0`
- 공급사 응답에 특정 날짜가 누락돼도 재고 0으로 취급
- 공급사 하나가 실패해도 나머지 공급사 결과는 정상 반환
- 실패한 공급사는 `failedSuppliers`에 반영

#### 2. 캐싱 전략

**결론: 변경 주기와 역할이 다른 데이터를 별도 캐시로 분리**

| 대상 | 캐시 | 주요 설정 | 목적 |
|---|---|---|---|
| 숙소 매핑 | Caffeine | `maximumSize=1000`, `expireAfterWrite=10m` | 정적 매핑의 DB 조회 감소 |
| 요금/재고 | Caffeine `AsyncCache` | TTL 부하 테스트로 결정 | 공급사 호출 감소 + Stampede 방지 |

##### 숙소 매핑 캐시

- `SearchStayService.search()`는 검색마다 `HotelMappingPort.findAllHotelMappingBySupplier()`를 호출
- 숙소 매핑은 `refresh()` 때만 변경되는 정적 데이터이므로 매번 DB를 읽을 필요가 없음
- `@Cacheable` + Caffeine으로 캐싱
- `HotelMappingPortAdapter.resolveOrCreateHotel()`에서 **신규 매핑이 실제 생성된 경우에만** 직접 evict

##### 요금/재고 캐시

- `SupplierSearchPortAdapter`에 Caffeine `AsyncCache` 적용
- Key: `(supplierCode, 숙소코드 배치, SearchCriteria)`
- `AsyncCache.get(key, mappingFunction)`의 동작을 이용해 Stampede 방지
  - 같은 Key에 동시에 요청이 들어오면 첫 요청의 진행 중인 `Future`에 나머지 요청이 합류
  - 별도 락 없이 동시 요청이 몰려도 실제 공급사 호출은 1번만 수행

##### TTL 검증

| TTL | 히트율(반복 1) | 히트율(반복 2) | 히트율(반복 3) | 평균 ± 표준편차 |
|---:|---:|---:|---:|---:|
| 45초 | 80.0% | 76.7% | 80.8% | **79.2% ± 2.2%p** |
| 60초 | 80.3% | 74.4% | 77.5% | 77.4% ± 2.9%p |

**결정: TTL 45초**

- 45초에서 히트율이 이론적 상한에 근접
  - 쏠림 80% + 인기 날짜 5개 가정 시 약 78.6%
- 60초로 늘려도 추가적인 개선이 확인되지 않음
- 따라서 **45초로 유지**

**정합성 가정**

- 검색 결과는 참고용
- 실제 예약 확정 시점에는 공급사에 재검증한다는 전제로 TTL 동안의 staleness를 허용
- 이 과제는 예약 흐름 자체를 구현하지 않으므로 재검증 단계는 설계 가정으로만 명시

**한계**

- mock-supplier 데이터만으로는 TTL 동안 실제 값이 얼마나 변하는지 측정할 수 없음
- 따라서 이번 부하 테스트가 검증한 것은 캐시의 **효율(히트율)**이지 staleness의 실제 비용이 아님
- 45초는 "더 늘려도 이득이 없다"는 상한의 근거이지, "45초까지 안전하다"는 하한의 근거는 아님

#### 3. Retry / CircuitBreaker

- **결정:** resilience4j-spring-boot3 + resilience4j-reactor
- **적용 위치:** `SupplierAClient` / `SupplierBClient`의 `searchSupplierAvailability()`
- **대안:** 직접 재시도 루프 구현
- **적용 이유:** resilience4j 애노테이션은 Spring AOP 프록시로 동작하므로 실제 외부 호출 메서드에 적용해야 함
  - `SupplierSearchPortAdapter` 내부 private 메서드의 self-invocation은 프록시를 거치지 않아 애노테이션이 적용되지 않음
  - 각 Client의 구현 메서드는 외부에서 호출되는 지점이므로 프록시 적용 가능

**재시도 정책**

- Retry가 CircuitBreaker보다 바깥에 위치
  - 재시도 한 번 한 번이 서킷에 개별 기록되도록 구성
- `SupplierCallException`에 `retryable` 플래그 추가
  - `true`: HTTP 5xx / 네트워크 오류
  - `false`: HTTP 4xx / Supplier B의 명백한 비즈니스 실패

##### max-attempts 성능 테스트

- mock-supplier에 `flaky` 모드 추가
  - 요청마다 독립적으로 N% 확률로 실패
- flaky 30% 조건에서 k6로 측정
- max-attempts 1 / 2 / 3 / 5를 각각 3회 반복

| max-attempts | 성공률 | p99 지연 | 이론값 `1 - 0.3ⁿ` |
|---:|---:|---:|---:|
| 1 | 66.1% ± 11.1%p | 50ms | 70.0% |
| 2 | 93.3% ± 2.9%p | 245ms | 91.0% |
| 3 | **96.7% ± 1.7%p** | **656ms** | 97.3% |
| 5 | 100.0% ± 0.0%p | 1,350ms ± 315ms | 99.76% |

**결정: `max-attempts=3`**

- 1 → 2회: 성공률 **+24.5%p**, p99 **+198ms**
- 2 → 3회: 성공률 **+2.8%p**, p99 **+414ms**
- 3 → 5회: 성공률 **+4.4%p**에 비해 지연시간이 약 3배 증가하고 변동성도 크게 증가

→ 성공률과 지연시간의 균형을 고려하면 **3회가 합리적인 지점**이라고 판단

#### 4. 병렬성 검증

##### Supplier 병렬 호출 검증

- mock-supplier에 인위적인 지연 적용
  - Supplier A: 300ms
  - Supplier B: 600ms
- 검색 API 응답시간을 측정해 순차 호출과 병렬 호출을 비교

| | 순차 예상 | 병렬 예상 | 실측 (3회 반복) |
|---|---:|---:|---:|
| 응답시간 | 900ms | ~600ms | **633ms ± 14ms** |
| 상세 | - | - | 649 / 623 / 627ms |

**결과**

- 실측값 633ms가 순차 예상치 900ms가 아닌 병렬 예상치 ~600ms에 근접
- `SupplierSearchPortAdapter.searchAll()`의 `Flux.flatMap`이 Supplier를 실제로 동시 호출한다는 것을 정량적으로 확인

##### 동시성 확장성 검증

- Spring Boot Actuator + WebClient(reactor-netty) 커넥션 풀 메트릭 노출
- 동시 사용자(VUs) 10 / 50 / 100 / 200으로 증가시키며 TPS·p95/p99·CPU·커넥션 풀 사용량 비교

##### 순간 접속 (`constant-vus`)

| VUs | TPS | 평균(ms) | p95(ms) | p99(ms) | 실패율 | CPU 평균/최대 | 커넥션 평균/최대 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 62 | 160 | 169 | 177 | 0.0% | 1.8% / 2.6% | 15 / 20 |
| 50 | 312 | 160 | 170 | 175 | 0.0% | 5.2% / 6.3% | 80 / 100 |
| 100 | 625 | 160 | 169 | 176 | 0.0% | 8.9% / 10.3% | 162 / 200 |
| 200 | 784 | **253** | **260** | **264** | **0.7%** | 8.7% / 11.1% | 335 / 363 |

**결과**

- VUs 10~100
  - TPS가 동시성에 거의 비례
  - 지연시간 약 160ms로 일정
  - CPU도 선형 증가
  - 애플리케이션 병목 없음
- VUs 200
  - 실패율 0.7%
  - p95 260ms로 지연 증가
  - CPU 최대 11.1%, 커넥션 풀 최대 363/500으로 리소스 여유
- 따라서 애플리케이션 리소스 고갈보다는 macOS의 `listen backlog` 기본값 128과 `constant-vus`의 시작 순간 200명 동시 접속 버스트가 원인이라고 판단

##### 점진적 접속 (`ramping-vus`)

- 0명 → VUs를 10초에 걸쳐 점진적으로 증가

| VUs | TPS | 평균(ms) | p95(ms) | p99(ms) | 실패율 | CPU 평균/최대 | 커넥션 평균/최대 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 49 | 159 | 165 | 171 | **0.0%** | 2.3% / 3.6% | 12 / 20 |
| 50 | 254 | 157 | 164 | 171 | **0.0%** | 4.7% / 7.1% | 64 / 100 |
| 100 | 514 | 155 | 159 | 161 | **0.0%** | 7.5% / 10.7% | 130 / 187 |
| 200 | 686 | 232 | 260 | 264 | **0.0%** | 8.5% / 14.2% | 277 / 363 |

**결과**

- VUs=200에서도 점진적으로 접속하면 실패율이 0%로 사라짐
- 지연시간 상승은 유지되지만 이는 실패가 아니라 커넥션 사용량 증가에 따른 자연스러운 큐잉 효과
- 동일한 VUs에서 접속 패턴에 따라 결과가 달라지는 것을 확인

**결론**

- 애플리케이션 자체의 리소스 고갈이나 병렬 처리 결함으로 보기 어려움
- `constant-vus`에서만 발생한 실패는 로컬 macOS 테스트 환경의 순간적인 연결 버스트 및 커널 제약의 영향으로 판단

### 테스트 스크립트

- `scripts/cache-load-test.sh`
  - 캐시 TTL 파라미터 및 반복 평균 측정
- `scripts/retry-load-test.sh`
- `retry-k6-test.js`
  - 재시도 횟수별 성능 측정
- `scripts/concurrency-load-test.sh`
- `concurrency-k6-test.js`
  - 병렬 호출 및 CPU / 커넥션 풀 폴링

### 참고 자료

- [Caffeine `AsyncCache` Javadoc](https://javadoc.io/doc/com.github.ben-manes.caffeine/caffeine/latest/com.github.benmanes.caffeine/com/github/benmanes/caffeine/cache/AsyncCache.html)
- [resilience4j — Retry와 CircuitBreaker 조합 가이드](https://resilience4j.readme.io/docs/getting-started-3)


## Day 5 - 타임아웃 값 검증 + `refresh()` 부분 실패 격리 버그 수정

### 수행 내용

- `SupplierWebClientConfig`의 연결·응답 타임아웃 값을 실측으로 검증
- 타임아웃 재현 테스트 중 `RefreshHotelCatalogService.refresh()`의 부분 실패 격리 누락을 발견하고 수정

### 의사결정

#### 1. 타임아웃(연결·응답) 값 검증

- **배경:** `SupplierWebClientConfig`의 `CONNECT_TIMEOUT=3s` / `RESPONSE_TIMEOUT=5s`가 왜 합리적인 값인지 실측 근거 필요
- **메커니즘 검증:**
  - mock-supplier를 8초 지연으로 설정 → 검색 API가 정확히 **5.115초**에 A를 포기하고 B 결과만 반환
  - 존재하지 않는 주소(`10.255.255.1`)로 연결 시도 → 로그에 `connection timed out after 3000 ms`로 **정확히 3000ms**에 실패
  - 두 타임아웃 모두 설정값과 오차 없이 동작함을 확인
- **값의 근거:**
  - `scripts/concurrency-load-test.sh`로 이미 실측해둔 정상 응답시간(VUs=100에서 p99 176ms, VUs=200에서 p99 264ms) 대비, 응답 타임아웃 5초는 약 **19~28배의 안전 마진**
  - "정상적으로 느림"과 "멈춤"을 확실히 구분할 수 있는 수준
  - 연결 타임아웃 3초는 TCP 핸드셰이크 실패를 판정하는 값이라 원래 짧아야 정상이지만, 넉넉하게 잡아 일시적 네트워크 지연까지 흡수하게 함
- **재시도와 겹쳤을 때의 한계:**
  - `max-attempts=3`과 조합하면 한 공급사를 최종적으로 포기하는 데 최악의 경우 `5초 × 3 + 백오프(200ms+400ms) ≈ 15.6초`
  - 검색 API치고 긴 시간이지만, 이후 요청은 서킷브레이커가 열려 즉시 차단되므로 "최초 1회성 비용"으로 감수 가능하다고 판단

#### 2. `refresh()` 부분 실패 격리 버그 수정

- **발견 경위:** 연결 타임아웃 재현 테스트 도중, `RefreshHotelCatalogService.refresh()`가 검색과 달리 공급사별 실패 격리가 없다는 것을 발견
  - 공급사 하나의 카탈로그 조회 실패가 `CommandLineRunner`를 실패시켜 애플리케이션 전체 기동을 막아버림 (로그: `Application run failed`)
  - 검색 경로에 공들여 만든 "부분 실패 허용" 원칙이 기동 시점에는 전혀 적용돼 있지 않았던 단일 장애점
- **결정:** `RefreshHotelCatalogPort.refresh()`의 반환 타입을 `void` → `List<SupplierCode>`(실패한 공급사 목록)로 변경
  - `SearchStayPort.SearchResult.failedSuppliers()`와 같은 패턴
- **구현:**
  - `RefreshHotelCatalogService`가 공급사별로 `try-catch(SupplierCallException)`해서 하나가 실패해도 나머지는 계속 진행
  - 실패한 공급사 코드만 모아서 반환
  - `UseCaseConfig`의 `CommandLineRunner`가 이 반환값을 받아 `WARN` 로그만 남기고 기동은 정상적으로 계속되게 함
- **재현 검증:** A의 base-url을 불능 주소로 만든 뒤 재기동 → `Application run failed` 없이 정상 기동, `숙소 목록 갱신 실패: [SUPPLIER_A]` 경고 로그만 남고, 검색 API는 B의 결과만으로 정상 응답하는 것을 확인
