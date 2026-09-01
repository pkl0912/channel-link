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
- 이유: 과제 요구사항 자체가 "Supplier별 요청/응답 형식이 도메인 계층으로 새어 나가지 않도록 경계를 두라"인데, 이게 Port·Adapter 패턴의 정의와 정확히 일치한다. 신규 Supplier를 추가할 때 Adapter 하나만 새로 작성하면 된다.

**3. 표준 모델과 영속 대상 분리**
- 대안: 공급사 응답을 그대로 캐싱하는 별도 테이블(RoomType 캐시)을 둘지 / 매핑 테이블만 영속할지
- 결정: 매핑(`HotelMapping`/`RoomTypeMapping`)만 영속. `Hotel`/`RoomType`/`DailyInventory`/`StayOffer`는 검색·갱신 요청마다 라이브로 다시 조립하는 값 객체로 두고 DB에 저장하지 않음
- 이유: `roomTypeName`/`maxOccupancy` 같은 필드는 재고와 요금 조회 응답에도 매번 포함돼 있어서, 별도로 캐싱해도 어차피 매번 다시 받아오는 값의 사본일 뿐이다. 또한, 요금과 재고의 원본은 외부에 있으므로 쌓아둘 필요가 없다고 명시하고 있어 매핑 외에는 영속하지 않는 쪽으로 판단
- 포기한 것: 조회 성능 최적화— 필요해지면 선택 구현으로 나중에 추가 가능

**4. `breakfastIncluded`/`currency`의 위치**
- 대안: 방의 정적 속성(`RoomType`)에 둘지 / 요금의 속성(`StayOffer`)에 둘지
- 결정: `StayOffer`
- 이유: 요금에 조식이 포함되는지는 같은 객실이라도 공급사마다다를 수 있다"고 명시돼 있다. 방 자체의 고정 속성이 아니라 가격에 딸린 조건이라고 판단

### 참고 자료
- [Hexagonal Architecture with Java and Spring](https://reflectoring.io/spring-hexagonal/)
