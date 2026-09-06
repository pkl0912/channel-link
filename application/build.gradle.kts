plugins {
    java
}

// 여기도 Spring 의존성을 넣지 않는다. 유스케이스는 순수 자바로 두고,
// Spring 빈으로 등록하는 건 bootstrap 모듈의 @Configuration에서 담당한다.
dependencies {
    implementation(project(":domain"))

    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.mockito:mockito-core:5.14.2")
}
