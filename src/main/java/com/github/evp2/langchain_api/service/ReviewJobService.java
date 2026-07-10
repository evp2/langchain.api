package com.github.evp2.langchain_api.service;

import com.github.evp2.langchain_api.model.ModelChoice;
import com.github.evp2.langchain_api.model.ReviewJob;
import com.github.evp2.langchain_api.model.ReviewResponse;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs PR reviews asynchronously and tracks their state. A submitted review executes on the
 * {@code jobExecutor} pool while the HTTP request returns immediately; callers poll {@link #get}
 * for progress and, eventually, the {@link ReviewResponse}.
 *
 * <p>The store is in-memory: jobs do not survive a restart and are not shared across instances.
 * That is sufficient for a single-instance deployment; swap the map for a shared store (Redis/DB)
 * if durability or horizontal scaling is needed.
 */
@Service
public class ReviewJobService {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobService.class);

    private final CodeReviewService reviews;
    private final ExecutorService jobExecutor;
    private final Map<String, ReviewJob> jobs = new ConcurrentHashMap<>();

    public ReviewJobService(CodeReviewService reviews, ExecutorService jobExecutor) {
        this.reviews = reviews;
        this.jobExecutor = jobExecutor;
    }

    /** Register a new job and start it on the background pool. Returns the PENDING job. */
    public ReviewJob submit(String prUrl, ModelChoice model) {
        String jobId = UUID.randomUUID().toString();
        ReviewJob job = ReviewJob.pending(jobId, prUrl, model, Instant.now());
        jobs.put(jobId, job);
        jobExecutor.submit(() -> run(jobId, prUrl, model));
        log.info("Submitted review job {} for {} ({})", jobId, prUrl, model);
        return job;
    }

    public Optional<ReviewJob> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    private void run(String jobId, String prUrl, ModelChoice model) {
        jobs.computeIfPresent(jobId, (id, j) -> j.running());
        try {
            ReviewResponse result = reviews.review(prUrl, model);
            jobs.computeIfPresent(jobId, (id, j) -> j.succeeded(result, Instant.now()));
            log.info("Review job {} succeeded", jobId);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            jobs.computeIfPresent(jobId, (id, j) -> j.failed(msg, Instant.now()));
            log.warn("Review job {} failed: {}", jobId, msg);
        }
    }
}
