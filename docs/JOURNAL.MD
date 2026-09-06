# JOURNAL

## Day 1 - 프로젝트 세팅 & 도메인 모델 설계

### 수행 내용
- Gradle 멀티모듈 프로젝트 세팅 (Groovy DSL, Java 21, Spring Boot 3.4)
    - `domain` / `application` / `adapter-in-web` / `adapter-out-supplier` / `adapter-out-persistence` / `bootstrap` / `mock-supplier` 7개 모듈로 분리
    - Mock Supplier는 본체와 같은 포트에서 자기 자신을 호출하는 문제를 피하기 위해 별도 프로세스(9090)로 분리
- 도메인 모델 설계 및
    - Supplier A/B의 서로 다른 응답을 통일할 표준 모델(`Hotel`, `RoomType`, `DailyInventory`, `StayOffer`)과, DB에 영속할 매핑 모델(`HotelMapping`, `RoomTypeMapping`)을 분리해서 설계
    - `HotelMappingRepository` Port와 Adapter 구현

### 의사결정

**1. 멀티모듈 vs 단일모듈**
- 대안: 패키지로만 레이어를 나누는 단일 모듈 / 서브프로젝트로 물리적으로 분리하는 멀티모듈
- 결정: 멀티모듈
- 이유: `domain` 모듈의 `build.gradle`에 Spring·JPA·WebClient 의존성을 넣지 않았다. 이렇게 하면 외부 연동 코드가 도메인 계층으로 새어 들어오는 순간 컴파일이 깨진다 — **빌드 자체가 경계를 강제**하게 만드는 게 목적
- 포기한 것: 모듈별 `build.gradle` 관리, 초기 세팅 시간 증가

**2. 헥사고날 아키텍처**
- 대안: Controller-Service-Repository 3계층 / 헥사고날(Port & Adapter)
- 결정: 헥사고날
- 이유: Supplier별 요청/응답 형식이 도메인 계층으로 새어 나가지 않도록 경계를 두어야 하는데, 이게 Port·Adapter 패턴의 정의와 정확히 일치한다. 신규 Supplier를 추가할 때 Adapter 하나만 새로 작성하면 된다.

**3. 표준 모델과 영속 대상 분리**
- 대안: 공급사 응답을 그대로 캐싱하는 별도 테이블(RoomType 캐시)을 둘지 / 매핑 테이블만 영속할지
- 결정: 매핑(`HotelMapping`/`RoomTypeMapping`)만 영속. `Hotel`/`RoomType`/`DailyInventory`/`StayOffer`는 검색·갱신 요청마다 라이브로 다시 조립하는 값 객체로 두고 DB에 저장하지 않음
- 이유: `roomTypeName`/`maxOccupancy` 같은 필드는 재고와 요금 조회 응답에도 매번 포함돼 있어서, 별도로 캐싱해도 어차피 매번 다시 받아오는 값의 사본일 뿐이다. 또한, 요금과 재고의 원본은 외부에 있으므로 쌓아둘 필요가 없어 매핑 외에는 영속하지 않는 쪽으로 판단
- 포기한 것: 조회 성능 최적화— 필요해지면 선택 구현으로 나중에 추가 가능

**4. `breakfastIncluded`/`currency`의 위치**
- 대안: 방의 정적 속성(`RoomType`)에 둘지 / 요금의 속성(`StayOffer`)에 둘지
- 결정: `StayOffer`
- 이유: 요금에 조식이 포함되는지는 같은 객실이라도 공급사마다 다를 수 있다. 방 자체의 고정 속성이 아니라 가격에 딸린 조건이라고 판단

### 참고 자료
- [Hexagonal Architecture with Java and Spring](https://reflectoring.io/spring-hexagonal/)

## Day 2 - Supplier 연동 어댑터 (레지스트리 패턴)

### 수행 내용
- `domain`에 `SupplierClient` Port 추가 — `fetchHotelCatalog()`, `searchAvailability(hotelCodes, criteria)` 두 메서드로 정의, `SearchCriteria` 공통 규약 작성
- `SupplierAClient`/`SupplierBClient` 구현 — WebClient로 mock-supplier 실제 호출, 각자 원본 응답을 표준 모델(`Hotel`/`RoomType`/`DailyInventory`/`StayOffer`)로 정규화
- `SupplierClientRegistry` 구현 — Map 기반 레지스트리 패턴
- 내부 식별자(`internalHotelId`, `internalRoomTypeId`) 생성 방식을 `UUID.randomUUID()`(v4)에서 UUIDv7로 교체 — `adapter-out-persistence`에 직접 구현

### 의사결정

**1. 실패를 하나의 예외로 통일**
- 대안: 각 어댑터가 자기 방식대로 예외를 던지게 둘지 / 공통 예외 타입을 Port 계약에 포함시킬지
- 결정: `SupplierCallException` 하나로 통일
- 이유: Supplier A는 HTTP 4xx/5xx로, Supplier B는 HTTP 200 + `resultCode`. B는 `resultCode != "0000"`이면 어댑터가 직접 확인해서 예외를 던지게 만들었다 

**2. 신규 Supplier 추가를 대비해 레지스트리 패턴 사용**
- 대안: `SupplierCode`별로 if/else나 switch로 분기해서 알맞은 어댑터를 고르게 할지 / 구현체를 Map에 등록해두고 꺼내 쓰는 레지스트리로 만들지
- 결정: `SupplierClientRegistry`로 레지스트리 패턴 적용
- 이유: if/else 방식이면 Supplier가 하나 늘 때마다 분기 코드를 계속 고쳐야 한다(OCP 위반). 반면, 레지스트리는 신규 Supplier를 추가할 때는 어댑터 클래스 하나를 `@Component`로 새로 작성하기만 하면 된다.

**3. 제네릭 봉투(`EnvelopeB<T>`) 역직렬화에 `ParameterizedTypeReference` 사용**
- 문제: Supplier B 응답은 `{resultCode, resultMessage, data}` 봉투 구조인데, `.bodyToMono(EnvelopeB.class)`처럼 `Class` 객체로 넘기면 제네릭 타입 소거 때문에 `data` 필드가 실제 타입(`PropertiesData`/`SearchData`)이 아니라 `LinkedHashMap`으로 역직렬화된다
- 해결: `ParameterizedTypeReference<EnvelopeB<PropertiesData>>() {}` 형태의 익명 서브클래스로 제네릭 타입 정보를 런타임까지 유지. 이 트릭은 호출 시점에 타입 파라미터가 고정돼 있어야 해서, `PropertiesData`용/`SearchData`용 메서드를 각각 따로 둠

**4. 내부 식별자를 UUIDv4 대신 UUIDv7로, 그리고 domain이 아니라 adapter-out-persistence에 배치**
- 대안: 랜덤 UUID(v4) 계속 쓸지 / 시간 정렬되는 UUIDv7로 바꿀지. 바꾼다면 이 생성 로직을 `domain`에 둘지 / `adapter-out-persistence`에 둘지, 직접 구현할지 / 라이브러리(`uuid-creator` 등)를 쓸지
- 결정: UUIDv7로 교체, 직접 구현, `adapter-out-persistence`에 배치
- 이유(v4 → v7): v4는 완전 무작위라 B-tree 인덱스에 무작위 위치로 꽂혀서 삽입마다 페이지 분할이 일어난다. v7은 앞 48비트가 밀리초 타임스탬프라 삽입이 시간순으로 인덱스 뒤쪽에 대체로 이어붙는 형태가 돼 인덱스 지역성이 좋다. 지금 데이터 건수로는 체감 차이가 없지만, 나중에 데이터가 늘었을 때를 대비한 선택
- 이유(domain이 아니라 adapter-out-persistence): 이 선택의 근거(B-tree 인덱스 지역성)는 저장소의 물리적 특성 때문이지 도메인 규칙이 아니다.
- 이유(직접 구현 vs 라이브러리): `uuid-creator` 같은 라이브러리도 있지만, ~20줄짜리 코드에 외부 의존성을 추가하는 것보다 직접 구현하고 검증하는 쪽을 택함. 
- 포기한 것: 같은 밀리초 안에서의 엄격한 단조 증가(monotonic) 보장 — `uuid-creator`의 `getTimeOrderedEpoch()`는 카운터로 이를 보장하지만, 이건 초당 수천 건 이상 생성하는 고빈도 상황에서나 의미가 있고 지금 매핑 생성 빈도로는 불필요하다고 판단

### 참고 자료
- [Spring `ParameterizedTypeReference` Javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/ParameterizedTypeReference.html)
- [RFC 9562 - UUID Version 7](https://www.rfc-editor.org/rfc/rfc9562.html#name-uuid-version-7)

## Day 3 - 통합 검색 API 완성 + 병렬 호출 방식 재검토 + 네이밍 정리

### 수행 내용
- 통합 검색 API 실제 구현 — `SearchStayPort`/`RefreshHotelCatalogPort`(domain), `SearchStayService`/`RefreshHotelCatalogService`(application), `StayController`(adapter-in-web)까지 전 계층 연결
- 검색 병렬 호출 방식을 WebClient의 Reactor 특성을 살린 방식으로 결정

### 의사결정

**1. WebClient의 Reactor 체인을 그대로 살리는 방식 결정**
- 대안: 계속 `SupplierPort.searchAvailability()`(blocking)를 가상 스레드 executor + `CompletableFuture`로 병렬 호출 / WebClient가 이미 non-blocking이라는 걸 살려서 `Mono`/`Flux`를 끝까지 안 끊고 병렬화
- 결정: 후자. 단, `Mono`가 domain/application으로 새어 나가면 안 되므로 **adapter-out-supplier 안에서만** 리액티브 체인을 유지하는 구조로 설계
  - domain에 새 Port `SupplierSearchPort` 추가 — 평범한 동기 인터페이스(`searchAll(...) : List<SupplierSearchOutcome>`)
  - 구현체 `SupplierSearchPortAdapter`(adapter-out-supplier)가 내부적으로 `Flux.fromIterable(...).flatMap(...)`으로 공급사를 동시에 호출하고, `.block()`은 맨 끝에 딱 한 번만
  - `SupplierAClient`/`SupplierBClient`는 `SupplierPort`(blocking 계약)와 별개로, 패키지 프라이빗 `ReactiveSupplierSearch`(Mono 리턴)를 같이 구현 — Spring이 `List<ReactiveSupplierSearch>`를 자동 수집해서 어댑터가 이걸 그대로 씀
- 이유: WebClient는 이미 Reactor Netty 기반 non-blocking 클라이언트다. 이걸 `.block()`으로 동기화했다가 가상 스레드로 다시 병렬화하는 건, 이미 있는 비동기성을 한 번 죽였다 다른 방식으로 되살리는 셈이라 불필요한 왕복이었다고 판단
- 결과: 'SupplierSearchPort` 하나만 동기 호출하면 되는 걸로 단순해짐. domain/application에는 여전히 `Mono`/`Flux`/`reactor` import가 0건

**2. domain 순수성을 일부 포기 — `application`/`adapter-out-supplier`에 Lombok 도입**
- 대안: 계속 `application`을 외부 의존성 0으로 유지(생성자 손으로 작성) / Lombok(`@AllArgsConstructor`, `@RequiredArgsConstructor`)으로 보일러플레이트 제거
- 결정: 도입. `application/build.gradle`, `adapter-out-supplier/build.gradle`에 Lombok 추가
- 이유(왜 포기해도 괜찮다고 봤는지): 지금까지 지켜온 "domain/application은 프레임워크 의존성 0"이라는 원칙은 **런타임에 특정 기술에 결합되는 것을 막기 위한 것**이었다. Lombok은 Spring·JPA·Reactor와 성격이 다르다 — 컴파일 타임에만 관여하는 코드 생성기라, 생성된 `.class`는 손으로 쓴 것과 완전히 동일하고 런타임에 Lombok 자체가 클래스패스에 있을 필요도 없다(`compileOnly`). 즉 "인프라 기술에 종속된다"는 진짜 위험은 없다고 판단해서, 원칙의 정신(런타임 결합 방지)은 지키되 문구(외부 의존성 0)만 완화함
- 포기한 것: `SearchStayService`/`RefreshHotelCatalogService` 생성자의 `List.copyOf()` 방어적 복사. `@AllArgsConstructor`는 단순 필드 대입만 생성해서 방어적 복사를 못 만든다. 이 서비스들의 생성자는 `UseCaseConfig`(우리가 직접 통제하는 composition root) 딱 한 곳에서만 호출되고, Spring이 매번 새 리스트를 만들어 넘겨주기 때문에 "호출부가 나중에 리스트를 건드리는" 시나리오 자체가 발생할 수 없다고 판단해서 감수
- domain 자체(모델·Port)에는 여전히 Lombok을 넣지 않음 — record만으로 이미 생성자·equals·toString이 다 나와서 필요가 없었음

**4. 숙소 목록 갱신(refresh) 시점 — "기동 시 1회"**
- 배경: 매핑은 공급사의 숙소 목록 API를 호출해 만든다. 언제 호출할지(기동 시 한 번? 주기적으로? 별도 명령으로?)는 직접 판단
- 대안: 기동 시 1회 / 주기적 스케줄 / 별도 관리용 트리거
- 결정: 기동 시 1회 (`bootstrap`의 `CommandLineRunner catalogWarmup`)
- 이유: 숙소 목록은 공통 규약상 "자주 안 바뀌는 정적 콘텐츠"이고, 재고·요금(매번 바뀜)과는 성격이 다르다. 이 성격 차이를 반영해 검색 때마다 다시 부르지 않고 앱 생애주기 동안 한 번만 불러도 충분하다고 판단. 과제 스코프상 별도 스케줄러·관리자 트리거까지는 두지 않음
- 왜 필수인지: `SearchStayService.search()`의 첫 단계가 매핑 조회이기 때문에, `refresh()`가 한 번도 안 돌면 매핑 테이블이 항상 비어 있어서 검색 결과가 영원히 빈 배열만 나온다

## Day 4 - 테스트 코드, 캐싱, 재시도/서킷브레이커, 성능·병렬성 검증

### 수행 내용
- 단위 테스트 추가 (`SearchStayServiceTest`, `SearchCriteriaTest`, `MoneyTest`)
- 캐싱 2종 도입 — 숙소 매핑 목록 캐시(`hotelMappingsBySupplier`), 요금/재고 캐시(`availabilityCache`)
- 재시도(Retry)/서킷브레이커(CircuitBreaker) 도입 (resilience4j)
- k6 기반 부하 테스트로 캐시 TTL, 재시도 횟수, 동시성 확장성을 실측 검증 

### 의사결정

**1. 테스트 코드 **
- 대안: 어댑터 계층(WebClient 파싱)부터 테스트할지 / 핵심 비즈니스 로직(`SearchStayService`)부터 테스트할지
- 결정: `SearchStayService`부터 — Mockito로 `SupplierPort`/`HotelMappingPort`/`SupplierSearchPort`를 모킹한 순수 단위 테스트
- 검증 항목: 모든 날짜에 재고 있으면 정상 반환 / 기간 중 하루라도 재고 0이면 `availableRooms=0` / 공급사 응답에 날짜가 누락돼도 0으로 취급 / 공급사 하나가 실패해도 나머지는 정상 반환되고 `failedSuppliers`에 반영

**2. 요금/재고 캐시 — 숙소 매핑 캐시와는 다른 문제**
- **숙소 매핑 캐시(`hotelMappingsBySupplier`)**: `SearchStayService.search()`가 검색마다 `HotelMappingPort.findAllHotelMappingBySupplier()`로 DB를 읽는데, 이 목록은 `refresh()` 때만 바뀌는 정적 데이터라 매번 다시 읽을 이유가 없다. `@Cacheable` + Caffeine(`maximumSize=1000, expireAfterWrite=10m`)으로 캐싱. 무효화는 `HotelMappingPortAdapter.resolveOrCreateHotel()`이 **신규 매핑을 실제로 생성했을 때만** 코드로 직접 evict 
- **요금/재고 캐시(`availabilityCache`)**: `SupplierSearchPortAdapter`에 Caffeine `AsyncCache` 도입, 키 = `(supplierCode, 숙소코드 배치, SearchCriteria)`. **스탬피드 방지**는 `AsyncCache.get(key, mappingFunction)`의 특성을 그대로 활용 — 같은 키로 동시에 여러 요청이 들어오면 첫 요청이 등록한 진행 중인 `Future`에 나머지가 합류해서, 별도 락 코드 없이도 동시 100건이 몰려도 실제 공급사 호출은 1번만 나감 **TTL**은 부하 테스트로 검증

  | TTL | 히트율(반복 1) | 히트율(반복 2) | 히트율(반복 3) | 평균 ± 표준편차 |
  |---|---|---|---|---|
  | 45초 | 80.0% | 76.7% | 80.8% | **79.2% ± 2.2%p** |
  | 60초 | 80.3% | 74.4% | 77.5% | **77.4% ± 2.9%p** |

  45초 부근에서 히트율이 이론적 상한(쏠림 80%·인기 날짜 5개 가정 시 계산값 ~78.6%)에 도달하고, 그 이상(60초) 늘려도 두 그룹 표본이 서로 겹쳐 통계적으로 유의미한 개선이 없음을 확인해서 45초로 유지. **정합성 오차 허용 범위**는 "검색 결과는 참고용, 실제 예약 확정 시점엔 공급사에 재검증한다"는 전제로 TTL(45초) 동안의 staleness를 허용 — 이 과제가 예약 흐름 자체를 구현하지 않아 그 재검증 단계는 없다는 점을 설계 가정으로 명시
- 한계: mock-supplier 데이터에 의해 "TTL 동안 실제로 값이 얼마나 달라지는가(staleness의 진짜 비용)"는 측정 불가능했다. 부하 테스트로 확인한 건 캐시의 효율(히트율) 쪽 뿐이고, 45초라는 값은 "그 이상 늘려도 이득이 없다"는 상한의 근거이지 "45초가 안전하다"는 하한의 근거는 아니다

**3. 재시도(Retry)/서킷브레이커(CircuitBreaker) + 성능 테스트**
- 대안: 직접 재시도 루프를 짤지 / resilience4j 애노테이션(`@Retry`, `@CircuitBreaker`)을 쓸지
- 결정: `resilience4j-spring-boot3` + `resilience4j-reactor`, `SupplierAClient`/`SupplierBClient`의 `searchSupplierAvailability()`에 애노테이션으로 적용
- 왜 `SupplierSearchPortAdapter`가 아니라 개별 Client에 붙였는지: resilience4j 애노테이션은 스프링 AOP 프록시로 동작하는데, `SupplierSearchPortAdapter` 내부의 private 메서드 self-invocation은 프록시를 안 타서 적용이 안 된다. 인터페이스 밖에서 실제로 호출되는 지점(각 Client의 구현 메서드)이라야 프록시가 걸림
- 데코레이터 순서: Retry가 CircuitBreaker보다 바깥 — 재시도 한 번 한 번이 서킷에 개별 기록돼야 실패율이 제대로 반영된다.
- 재시도 대상 구분: `SupplierCallException`에 `retryable` 플래그를 추가 — HTTP 5xx·네트워크 오류만 `true`, HTTP 4xx와 B의 명백한 비즈니스 실패(`resultCode`)는 `false`
- **max-attempts 성능 테스트**: mock-supplier에 "flaky" 모드(요청마다 독립적으로 N% 확률로 실패)를 추가하고, k6로 성공률·p99 지연시간을 max-attempts 1/2/3/5로 측정 (flaky 30%, 3회 반복 평균±표준편차)

  | max-attempts | 성공률 | p99 지연 | 이론값(1-0.3ⁿ) |
  |---|---|---|---|
  | 1 | 66.1% ± 11.1%p | 50ms | 70.0% |
  | 2 | 93.3% ± 2.9%p | 245ms | 91.0% |
  | 3 | 96.7% ± 1.7%p | 656ms | 97.3% |
  | 5 | 100.0% ± 0.0%p | 1,350ms ± 315ms | 99.76% |

  성공률 vs 지연 비교: 1→2회는 압도적 이득(+24.5%p / +198ms), 2→3회는 이득은 작아도 방향은 맞음(+2.8%p / +414ms), 3→5회는 명백히 손해(+4.4%p 얻자고 지연이 3배 뛰고 변동성까지 폭증) → **max-attempts=3**이 합리적인 지점

**4. 병렬성 검증 — k6로 실측**
- 처음엔 bash + curl + xargs로 부하 스크립트를 짰으나 k6로 교체 — 위와 동일한 이유
- **병렬 호출 자체를 증명**: mock-supplier에 인위적 지연(A=300ms, B=600ms)을 걸고 검색 API 응답시간을 측정

  | | 순차였다면 | 병렬이면 | 실측 (3회 반복) |
  |---|---|---|---|
  | 예상/결과 | 900ms | ~600ms | **633ms ± 14ms** (649/623/627ms) |

  실측이 순차 예상치(900ms)가 아니라 병렬 예상치(~600ms)에 딱 붙어서, `SupplierSearchPortAdapter.searchAll()`의 `Flux.flatMap`이 실제로 공급사를 동시 호출한다는 걸 정량적으로 확인
- **동시성 확장성 검증**: Spring Boot Actuator + WebClient(reactor-netty) 커넥션 풀 메트릭(`.metrics(true, ...)`)을 노출하고, 동시 사용자(VUs) 10/50/100/200으로 늘려가며 TPS·p95/p99·CPU·커넥션 풀 사용량을 비교

  순간 접속(`constant-vus`, 테스트 시작 시 VUs명이 한꺼번에 접속):

  | VUs | TPS | 평균(ms) | p95(ms) | p99(ms) | 실패율 | CPU 평균/최대 | 커넥션 평균/최대 |
  |---|---|---|---|---|---|---|---|
  | 10 | 62 | 160 | 169 | 177 | 0.0% | 1.8% / 2.6% | 15 / 20 |
  | 50 | 312 | 160 | 170 | 175 | 0.0% | 5.2% / 6.3% | 80 / 100 |
  | 100 | 625 | 160 | 169 | 176 | 0.0% | 8.9% / 10.3% | 162 / 200 |
  | 200 | 784 | **253** | **260** | **264** | **0.7%** | 8.7% / 11.1% | 335 / 363 |

  10~100까지는 TPS가 동시성에 거의 비례해서 이상적으로 스케일, 지연시간은 ~160ms로 일정, CPU는 선형 증가 — 병목 없음. 200에서 처음으로 실패율 0.7% 발생 + 지연 급증. CPU(11%)·커넥션 풀(363/500) 모두 여유 있어 리소스 고갈이 아니라, macOS 커널의 listen backlog(기본값 128)를 `constant-vus`가 만드는 "시작 순간 200명 동시 접속" 버스트가 넘겨서 발생한 것으로 확인

  점진적 접속(`ramping-vus`, 0명→VUs명을 10초에 걸쳐 증가):

  | VUs | TPS | 평균(ms) | p95(ms) | p99(ms) | 실패율 | CPU 평균/최대 | 커넥션 평균/최대 |
  |---|---|---|---|---|---|---|---|
  | 10 | 49 | 159 | 165 | 171 | **0.0%** | 2.3% / 3.6% | 12 / 20 |
  | 50 | 254 | 157 | 164 | 171 | **0.0%** | 4.7% / 7.1% | 64 / 100 |
  | 100 | 514 | 155 | 159 | 161 | **0.0%** | 7.5% / 10.7% | 130 / 187 |
  | 200 | 686 | 232 | 260 | 264 | **0.0%** | 8.5% / 14.2% | 277 / 363 |

  같은 VUs=200이어도 접속을 점진적으로 늘리면 실패율이 완전히 0%로 사라짐(지연시간 상승은 그대로 남음 — 이건 실패가 아니라 커넥션 사용량 증가에 따른 자연스러운 큐잉 효과)
  - 결론: 애플리케이션의 결함이 아니라 "실제 트래픽은 이렇게 순간적으로 몰리지 않는다"를 보여준 결과 — 로컬 macOS 테스트 환경의 커널 제약이었을 뿐.
- 스크립트: `scripts/cache-load-test.sh`(캐시 TTL 파라미터 + 반복 평균), `scripts/retry-load-test.sh` + `retry-k6-test.js`(재시도 횟수), `scripts/concurrency-load-test.sh` + `concurrency-k6-test.js`(병렬 + CPU/커넥션 풀 폴링) 

### 참고 자료
- [Caffeine `AsyncCache` Javadoc](https://javadoc.io/doc/com.github.ben-manes.caffeine/caffeine/latest/com.github.benmanes.caffeine/com/github/benmanes/caffeine/cache/AsyncCache.html)
- [resilience4j — Retry와 CircuitBreaker 조합 가이드](https://resilience4j.readme.io/docs/getting-started-3)

