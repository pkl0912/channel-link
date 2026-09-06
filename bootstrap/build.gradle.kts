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
    // OpenApiConfig가 io.swagger.v3.oas.models.OpenAPI를 컴파일 타임에 참조하기 위함
    // (springdoc 본체는 adapter-in-web에 있고, 여긴 그 타입만 필요)
    compileOnly("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
