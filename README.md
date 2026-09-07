# channel-link

여러 상품 공급사(Supplier)의 서로 다른 API를 하나의 **표준 모델로 통합**하여, 단일 검색 API로 노출하는 백엔드 서비스

설계 의사결정과 트레이드오프는 [`docs/JOURNAL.MD`](docs/JOURNAL.MD)에, AI 페어 프로그래밍 활용 내역은 [`docs/AI.md`](docs/AI.md)

## 목차

* [체크리스트](#체크리스트)
* [기술 스택](#기술-스택)
* [시스템 아키텍처](#시스템-아키텍처)
* [주요 설계 의사결정](#주요-설계-의사결정)
* [트레이드오프 및 한계점](#트레이드오프-및-한계점)
* [연동 지표 · 모니터링 설계](#연동-지표--모니터링-설계)
* [실행 방법](#실행-방법)
* [API 명세](#api-명세)
* [프로젝트 구조](#프로젝트-구조)

## 체크리스트

### 기능

* [x] 통합 검색 API — `GET /api/v1/stays/search`
* [x] 서로 다른 실패 신호(A: HTTP 상태 / B: HTTP 200 + `resultCode`)를 `SupplierCallException` 하나로 통일
* [x] 부분 실패 허용 — 공급사 하나가 실패해도 정상 공급사의 결과 반환, `failedSuppliers`로 실패 공급사 표시
* [x] 연박 예약 가능 객실 수 계산 — 기간 내 날짜별 잔여 객실 수의 최솟값
* [x] 숙소/객실 타입 내부 식별자 매핑 — 최초 조회 시 자동 생성 후 재사용
* [x] 숙소 목록 갱신 — 애플리케이션 기동 시 1회 실행, 공급사 하나가 실패해도 나머지로 계속 진행
* [x] 신규 공급사 추가에 열린 구조 — `SupplierCode` 상수와 어댑터 구현체 추가만으로 확장, 기존 검색 로직은 수정 없음

### 추가 구현

* [x] 재시도 — resilience4j `@Retry`, 5xx·타임아웃·네트워크 오류만 재시도 (`SupplierCallException.retryable()` 기준)
* [x] 서킷브레이커 — resilience4j `@CircuitBreaker`, 공급사별 독립 인스턴스(`supplierA`/`supplierB`)
* [x] 요금/재고 캐시 — TTL 설계 및 캐시 스탬피드 방지, 부하 테스트를 통한 TTL 비교
* [x] API 문서 자동화 — springdoc-openapi(Swagger UI)
* [x] 단위 테스트 — 핵심 도메인 로직 및 값 객체 검증
* [x] 성능/동시성 부하 테스트 — k6 기반 재현 가능한 스크립트
* [x] 연동 지표 · 모니터링 설계 — Supplier별 성공률/응답 지연은 `/actuator/prometheus`로 실측 확인, 타임아웃 비율은 설계

## 기술 스택

| 분류       | 사용 기술                                          |
| -------- | ---------------------------------------------- |
| 언어 / 런타임 | Java 21 (Virtual Threads)                      |
| 프레임워크    | Spring Boot 3.4.1                              |
| 빌드       | Gradle (Kotlin DSL), 멀티모듈                      |
| 영속성      | Spring Data JPA + H2                           |
| 캐시       | Spring Cache + Caffeine (`AsyncCache`)         |
| 재시도      | resilience4j (Retry, CircuitBreaker) + Reactor |
| API 문서   | springdoc-openapi (Swagger UI)                 |
| 테스트      | JUnit 5, Mockito, AssertJ                      |
| 부하 테스트   | k6, Bash + Python                              |

## 시스템 아키텍처

헥사고날(Port & Adapter) 아키텍처를 멀티모듈로 구성

`domain`/`application` 모듈에는 Spring·JPA·WebClient 등 프레임워크 의존성을 두지 않아, 아키텍처 경계를 빌드 단계에서 강제

### 모듈 별 설명

* **domain**: 표준 모델, 매핑 모델, Port 인터페이스
* **application**: Port(in)의 구현체인 유스케이스. Spring 의존성 없이 순수 Java로 작성
* **adapter-in-web**: REST Controller, Swagger
* **adapter-out-supplier**: 공급사 HTTP 연동, WebClient/Reactor, 캐시, Retry/CircuitBreaker
* **adapter-out-persistence**: 매핑 테이블 JPA 영속화
* **bootstrap**: 모든 모듈을 조립하는 최종 애플리케이션
* **mock-supplier**: 별도 프로세스(9090)로 실행되는 공급사 A/B Mock API

### 숙소 목록 갱신

애플리케이션 기동 시 `CommandLineRunner`가 1회 실행됨

`RefreshHotelCatalogService`가 등록된 모든 `SupplierPort`의 `fetchHotelCatalog()`를 호출하여 `HotelMapping`과 `RoomTypeMapping`을 생성

공급사 하나의 호출이 실패해도 나머지 공급사로 계속 진행함. 실패한 공급사는 `WARN` 로그만 남기고, 애플리케이션은 정상적으로 기동됨 — 해당 공급사는 매핑이 비어 있어 다음 갱신 전까지 검색 결과에서 빠짐

### 통합 검색

<img src="channel-flow.jpeg" alt="통합 검색 데이터 흐름" width="600">

### 데이터 모델

`HotelMapping`/`RoomTypeMapping`만 영속화하며, 요금과 재고는 매 검색 시 공급사에서 조회

각 Mapping 테이블은 `(supplier_code, hotel_code[, room_type_code])`에 유니크 제약을 가지며, `internal_hotel_id`/`internal_room_type_id`를 PK로 사용

<img src="channel-erd.jpeg" alt="ERD" width="600">

## 주요 설계 의사결정

### 1. 멀티모듈 + 헥사고날

`domain`/`application`의 빌드 파일에 프레임워크 의존성을 원천 차단하여, 아키텍처 경계를 코드 리뷰가 아닌 **빌드 단계에서 강제**

### 2. 매핑만 영속, 요금/재고는 라이브 조회 + 짧은 TTL 캐시

숙소 목록은 변경 빈도가 낮아 매핑 정보만 DB에 영속화하고, 요금/재고는 실시간성이 중요하므로 DB에는 저장하지 않고 검색 시 공급사에서 조회

다만 매 요청마다 공급사를 다시 부르는 비용을 줄이기 위해 별도의 인메모리 캐시(Caffeine `AsyncCache`, TTL 45초)는 둠 — "저장 안 함"은 DB 영속화 기준이며, 짧은 TTL의 메모리 캐시와는 별개 계층 ([6번](#6-요금재고-캐시) 참고)

### 3. `SupplierCallException`으로 실패 통일

Supplier A의 HTTP 상태 기반 실패와 Supplier B의 `HTTP 200 + resultCode` 기반 실패를 하나의 예외 타입으로 통일

예외 내부에 `retryable` 여부를 담아 5xx·네트워크 오류만 재시도하도록 구성

### 4. 레지스트리 패턴으로 공급사 확장

`SupplierPort`/`ReactiveSupplierSearch` 구현체를 Spring이 List<T>로 자동 수집하도록 구성

신규 공급사 추가 시 `SearchStayService`/`SupplierSearchPortAdapter` 등 기존 검색 유스케이스는 수정하지 않고, `SupplierCode`에 상수를 추가하고 해당 어댑터 구현체를 추가하는 것만으로 확장 (식별자를 enum으로 관리하는 부분 자체는 파일 수정이 필요하지만, 기존 로직에 분기/조건문을 추가하는 수정은 없음)

### 5. 도메인은 동기, Adapter 내부는 리액티브

`SupplierSearchPort`는 동기 인터페이스로 유지하고, 실제 Supplier 호출이 발생하는 `adapter-out-supplier` 내부에서만 `Flux.flatMap`을 사용하여 공급사를 병렬 호출

리액티브 타입은 Adapter 외부로 노출하지 않음

**Virtual Thread와 Reactor를 함께 쓰는 이유**: 애플리케이션/도메인 계층(Tomcat 요청 처리)은 `spring.threads.virtual.enabled=true`로 Virtual Thread 기반 동기 모델을 유지하고, 다수 Supplier를 동시에 호출해야 하는 `adapter-out-supplier`에서만 WebClient/Reactor로 I/O를 병렬화함. 
### 6. 요금/재고 캐시

Caffeine `AsyncCache.get(key, mappingFunction)`을 사용 — 캐시 미스 시 생성되는 Supplier 호출의 `Future`를 동일 키로 들어오는 요청들이 공유하도록 해서 캐시 스탬피드를 방지 (Caffeine이 키당 정확히 한 번만 계산을 실행함을 보장)

캐시 키는 `(supplierCode, 숙소 코드 배치, SearchCriteria)`로 구성, TTL은 부하 테스트를 통해 **45초**로 설정

### 7. Retry + CircuitBreaker

`mock-supplier`의 `flaky` 모드를 활용하여 실패 확률을 조절하고 Retry 횟수별 성공률과 p99 지연시간을 비교

이를 바탕으로 `max-attempts=3`으로 설정. `resilience4j.retry.retry-aspect-order=1` / `circuitbreaker.circuit-breaker-aspect-order=2`로 Retry가 CircuitBreaker보다 바깥에서 동작하도록 명시적으로 구성(재시도 한 번 한 번이 서킷에 개별 기록됨) — 애노테이션을 같이 쓸 때 실제 decorator 순서는 이 aspect-order 설정이 결정함

공급사별로 별도의 CircuitBreaker/Retry 인스턴스를 사용해 장애를 격리 — 하나가 열려도 다른 공급사 호출에는 영향 없음

재시도 대상 판정은 `SupplierCallException.retryable()`을 `RetryableSupplierExceptionPredicate`(`retry-exception-predicate` 설정으로 등록)가 읽어 결정 — 5xx·타임아웃·네트워크 오류는 `true`, 4xx 및 Supplier B의 `resultCode` 기반 비즈니스 실패는 `false`

### 8. UUIDv7

내부 식별자에 UUIDv4 대신, 시간 순서 특성을 가진 UUIDv7을 사용하여 B-tree 인덱스의 삽입 locality를 고려. UUIDv4는 완전 무작위라 삽입 위치가 분산되는 반면, UUIDv7은 타임스탬프 기반이라 생성 순서에 가깝게 삽입됨


## 트레이드오프 및 한계점

* **캐시 TTL의 staleness 비용 실측 불가**: 부하 테스트로 확인한 것은 캐시 히트율이며, 45초는 "더 늘려도 히트율 개선이 크지 않은 지점"의 근거임. 실제 데이터 변경 빈도와 허용 가능한 Staleness는 추가 검증이 필요
* **배치 내부는 순차 처리**: 한 공급사가 50개를 초과하는 숙소를 보유할 경우 배치를 나누고 `concatMap`으로 순차 처리함. 현재 규모에서는 문제가 없지만, 숙소가 대량으로 증가하면 `flatMap + concurrency 제한`으로 변경 가능.


## 연동 지표 · 모니터링 설계

Supplier별 성공률·응답 지연·타임아웃 비율을 관찰하기 위한 설계. `micrometer-registry-prometheus`를 실제로 추가하고 `/actuator/prometheus`를 열어 아래 내용을 직접 확인함 (Grafana 대시보드 구축까지는 하지 않음)

### 성공률 · 응답 지연 — resilience4j 기본 메트릭으로 확보 (실측 확인)

`resilience4j-spring-boot3`는 `MeterRegistry` 빈이 있으면 `@CircuitBreaker`/`@Retry` 호출 결과를 자동으로 Micrometer 메트릭에 바인딩함. 

`name` 태그가 실제로 `supplierA`/`supplierB`로 분리되어 나오는 것을 확인 — 코드 추가 없이 resilience4j 애노테이션 설정만으로 확보됨

### 타임아웃 비율 — 커스텀 Counter 필요 (미구현, 설계만)

CircuitBreaker의 `kind` 태그는 성공/실패/무시 여부만 구분하고, 실패 원인(타임아웃·5xx·4xx 비즈니스 실패)을 세분화하지 않음. `SupplierAClient`/`SupplierBClient`의 기존 에러 매핑 지점(`.onErrorMap`)에 실패 원인별 `Counter`를 추가하는 방식으로 설계

타임아웃 비율 = `reason="timeout"` 건수 / 전체 실패 건수

### 활용 방안

Prometheus가 `/actuator/prometheus`를 스크래핑하고 Grafana로 시각화. 공급사별 성공률이 임계치 이하로 떨어지거나 타임아웃 비율이 급증하면 알림 기능으로 확장 가능


## 실행 방법

### 사전 요구사항

* JDK 21

### 1. Mock Supplier 실행

```bash
./gradlew :mock-supplier:bootRun
```

* Port: `9090`
* 실제 외부 공급사 API를 대신하여 정상 응답 및 장애 상황을 재현

### 2. 애플리케이션 실행

```bash
./gradlew :bootstrap:bootRun
```

* Port: `8080`
* 애플리케이션 기동 직후 숙소 목록 갱신이 1회 실행

### 3. 검색 API 호출

```bash
curl 'http://localhost:8080/api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0'
```

* Swagger UI: `http://localhost:8080/swagger-ui/index.html`
* H2 Console: `http://localhost:8080/h2-console`

    * JDBC URL: `jdbc:h2:mem:channel-link`
    * User: `sa`
    * Password: 없음

## API 명세

Swagger UI(`/swagger-ui/index.html`)

### `GET /api/v1/stays/search`

날짜·인원 조건으로 등록된 모든 공급사를 검색하여 하나의 표준 응답으로 반환

**Query Parameters**

| 이름         | 타입                    | 필수 | 설명                       |
| ---------- | --------------------- | -- | ------------------------ |
| `checkIn`  | `date` (`YYYY-MM-DD`) | O  | 체크인 날짜                   |
| `checkOut` | `date` (`YYYY-MM-DD`) | O  | 체크아웃 날짜 (`checkIn`보다 이후) |
| `adults`   | `int`                 | O  | 성인 수 (1 이상)              |
| `children` | `int`                 | X  | 어린이 수 (기본 0)             |


## 프로젝트 구조

```text
channel-link
├── domain
├── application
├── adapter-in-web
├── adapter-out-supplier
├── adapter-out-persistence
├── bootstrap
└── mock-supplier
```

각 모듈의 상세 역할은 [시스템 아키텍처](#시스템-아키텍처)를 참고


