package com.github.evp2.langchain_api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
// The "test" profile (src/test/resources/application-test.properties) supplies a dummy
// inference-profile ARN and disables the startup STS credential check, so the context
// loads with no AWS credentials and no network access.
@ActiveProfiles("test")
class ApplicationTests {

  @Test
  void contextLoads() {
  }

}
