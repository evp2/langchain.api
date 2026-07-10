package com.github.evp2.langchain_api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcExecutorServiceTest {

    private final ExecutorService pool =
            new MdcExecutorService(Executors.newSingleThreadExecutor());

    @AfterEach
    void tearDown() throws InterruptedException {
        MDC.clear();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void submittedCallableSeesSubmittersMdc() throws Exception {
        MDC.put("requestId", "req-123");
        Future<String> seen = pool.submit(() -> MDC.get("requestId"));
        assertThat(seen.get(5, TimeUnit.SECONDS)).isEqualTo("req-123");
    }

    @Test
    void submittedRunnableSeesSubmittersMdc() throws Exception {
        MDC.put("requestId", "req-456");
        String[] seen = new String[1];
        pool.submit(() -> seen[0] = MDC.get("requestId")).get(5, TimeUnit.SECONDS);
        assertThat(seen[0]).isEqualTo("req-456");
    }

    @Test
    void workerMdcIsRestoredAfterTask() throws Exception {
        MDC.put("requestId", "req-789");
        pool.submit(() -> MDC.get("requestId")).get(5, TimeUnit.SECONDS);

        // A task submitted with no MDC context must not inherit leftovers from the previous task.
        MDC.clear();
        Future<String> leaked = pool.submit(() -> MDC.get("requestId"));
        assertThat(leaked.get(5, TimeUnit.SECONDS)).isNull();
    }
}
