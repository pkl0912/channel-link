plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":domain"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    implementation("com.github.ben-manes.caffeine:caffeine")

    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.4.0")
    implementation("io.github.resilience4j:resilience4j-reactor:2.4.0")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}
tasks.named<Jar>("jar") {
    enabled = true
}
