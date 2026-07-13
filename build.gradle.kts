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

    // Spring AI MCP server (servlet / Streamable-HTTP) — exposes the review services as MCP tools
    // on the existing port. Spring AI 2.0 targets Spring Boot 4.1 / Framework 7 (this stack).
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")

    // STS: used at startup to verify AWS credentials resolve and are not expired (GetCallerIdentity).
    implementation("software.amazon.awssdk:sts:2.41.34")
    // Apache HTTP client: configured with a widened socket-read timeout for slow Bedrock responses.
    implementation("software.amazon.awssdk:apache-client:2.41.34")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 moved the @WebMvcTest slice into the per-module webmvc test artifact.
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    // Standalone (shaded) WireMock avoids Jetty / servlet-API clashes with Spring Boot 4.
    testImplementation("org.wiremock:wiremock-standalone:3.13.1")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform {
        // "eval" tests call the real Bedrock backend (slow, costs money, needs AWS creds).
        // Opt in with: ./gradlew test -PincludeEvals
        if (!project.hasProperty("includeEvals")) {
            excludeTags("eval")
        }
    }
}
