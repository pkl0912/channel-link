plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// Mock은 채점 대상이 아니다 — 여기 코드 품질에 시간 쓰지 말 것 (부록 A.3).
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
}
