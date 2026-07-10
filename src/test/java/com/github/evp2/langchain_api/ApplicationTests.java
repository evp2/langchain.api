package com.github.evp2.langchain_api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
// Supply a dummy inference-profile ARN so the context loads without the real env var.
// Building the Bedrock client with this id makes no network call; it would only fail on invoke.
@TestPropertySource(properties =
        "INFERENCE_PROFILE_ARN=arn:aws:bedrock:us-east-1:000000000000:application-inference-profile/test-dummy")
class ApplicationTests {

  @Test
  void contextLoads() {
  }

}
