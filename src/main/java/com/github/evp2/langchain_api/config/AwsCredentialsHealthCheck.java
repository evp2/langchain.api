package com.github.evp2.langchain_api.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

/**
 * Verifies at startup that AWS credentials resolve and are still valid, failing fast if not.
 *
 * <p>Merely resolving the credential chain is not enough — expired session tokens resolve fine and
 * only fail when a request is actually signed and sent. So this makes one real, permission-free
 * STS {@code GetCallerIdentity} call. On any failure it throws, aborting startup with a clear
 * message, rather than letting the app boot and 502 on the first model call.
 */
@Component
// Disabled in tests (aws.health-check.enabled=false) so a context load makes no live STS call.
@ConditionalOnProperty(name = "aws.health-check.enabled", havingValue = "true", matchIfMissing = true)
public class AwsCredentialsHealthCheck {

    private static final Logger log = LoggerFactory.getLogger(AwsCredentialsHealthCheck.class);

    private final BedrockProperties props;

    public AwsCredentialsHealthCheck(BedrockProperties props) {
        this.props = props;
    }

    @PostConstruct
    void verify() {
        try (StsClient sts = StsClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build()) {
            GetCallerIdentityResponse id = sts.getCallerIdentity();
            log.info("AWS credentials verified at startup: account={}, arn={}", id.account(), id.arn());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IllegalStateException(
                    "AWS credential check failed at startup (STS GetCallerIdentity). The app cannot call Bedrock. "
                            + "Provide valid, unexpired credentials (e.g. refresh the assumed-role session) and restart. "
                            + "Cause: " + msg, e);
        }
    }
}
