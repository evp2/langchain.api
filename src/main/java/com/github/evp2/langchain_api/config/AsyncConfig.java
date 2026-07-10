package com.github.evp2.langchain_api.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Thread pool used to run the three specialist reviewers concurrently. */
@Configuration
public class AsyncConfig {

    /** Small fixed pool — one thread per specialist dimension. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService reviewExecutor() {
        return Executors.newFixedThreadPool(3);
    }
}
