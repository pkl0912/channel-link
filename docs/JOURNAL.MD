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
