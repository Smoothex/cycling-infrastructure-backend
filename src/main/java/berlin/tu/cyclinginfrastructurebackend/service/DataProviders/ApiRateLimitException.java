package berlin.tu.cyclinginfrastructurebackend.service.DataProviders;

import java.time.Instant;

/**
 * Thrown when an external API rejects requests because a rate limit or quota is
 * exhausted (HTTP 429, or 503 for providers that shed load that way).
 * <p>
 * Signals to the enrichment scheduler that the current batch should be aborted
 * and the pipeline paused instead of retrying request by request.
 */
public class ApiRateLimitException extends RuntimeException {

    private final Instant retryAt;

    public ApiRateLimitException(String message, Instant retryAt, Throwable cause) {
        super(message, cause);
        this.retryAt = retryAt;
    }

    /**
     * @return the instant at which the API is expected to accept requests again,
     *         or {@code null} if the API gave no hint (caller picks a backoff)
     */
    public Instant getRetryAt() {
        return retryAt;
    }
}
