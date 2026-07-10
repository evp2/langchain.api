package com.github.evp2.langchain_api.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Thread pools used by the review pipeline. */
@Configuration
public class AsyncConfig {

    /**
     * Small fixed pool — one thread per specialist dimension. Wrapped so tasks inherit the
     * submitting request's MDC (e.g. {@code requestId}), keeping specialist log lines correlated
     * to the originating API call.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService reviewExecutor() {
        return new MdcExecutorService(Executors.newFixedThreadPool(3));
    }

    /**
     * Runs whole review jobs off the request thread so the HTTP call can return 202 immediately.
     * Kept separate from {@link #reviewExecutor()}: each job fans out onto that specialist pool, so
     * sharing one pool could starve or deadlock when several jobs run at once.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService jobExecutor() {
        return new MdcExecutorService(Executors.newFixedThreadPool(4));
    }
}
