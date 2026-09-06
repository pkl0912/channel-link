plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":adapter-in-web"))
    implementation(project(":adapter-out-supplier"))
    implementation(project(":adapter-out-persistence"))
    implementation("org.springframework.boot:spring-boot-starter")
    // 부하 테스트 시 CPU·커넥션 풀 사용량을 /actuator/metrics로 관찰하기 위함
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    compileOnly("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")
    // Cache 부하테스트 진단용
    compileOnly("com.github.ben-manes.caffeine:caffeine")
    compileOnly("org.springframework.boot:spring-boot-starter-cache")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
