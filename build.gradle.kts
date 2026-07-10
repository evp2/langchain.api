plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.github.evp2"
version = "0.0.1-SNAPSHOT"
description = "langchain.api"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val langchain4jVersion = "1.17.2"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // OpenAPI / Swagger UI (springdoc 3.x targets Spring Boot 4 / Spring Framework 7)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    // LangChain4j core + AWS Bedrock (Converse API) integration
    implementation("dev.langchain4j:langchain4j:$langchain4jVersion")
    implementation("dev.langchain4j:langchain4j-bedrock:$langchain4jVersion")

    // STS: used at startup to verify AWS credentials resolve and are not expired (GetCallerIdentity).
    implementation("software.amazon.awssdk:sts:2.41.34")
    // Apache HTTP client: configured with a widened socket-read timeout for slow Bedrock responses.
    implementation("software.amazon.awssdk:apache-client:2.41.34")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
