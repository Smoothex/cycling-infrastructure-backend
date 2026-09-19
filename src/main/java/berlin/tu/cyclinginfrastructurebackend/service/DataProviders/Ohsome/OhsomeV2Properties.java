package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/** Configuration for demand-driven, buffered tile/month snapshots. */
@Component
@ConfigurationProperties(prefix = "ohsome.v2")
public class OhsomeV2Properties implements InitializingBean {

    private URI baseUrl = URI.create("https://api.heigit.org/ohsome-api/v2-rc");
    private String apiKey = "";
    private Path cachePath = Path.of("./data/ohsome/v2/tiles-v1");
    private double gridSizeDegrees = 0.1;
    private double bufferDegrees = 0.01;
    private String filter = "type:way and highway=*";
    private boolean clip = false;
    private Duration downloadInterval = Duration.ofSeconds(60);
    private int maxRequestsPerDay = 250;
    private int maxRetries = 3;
    private Duration initialRetryDelay = Duration.ofMinutes(1);
    private Duration maxRetryDelay = Duration.ofMinutes(30);
    private Duration connectTimeout = Duration.ofSeconds(30);
    private Duration requestTimeout = Duration.ofMinutes(30);

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        if (baseUrl == null || !baseUrl.isAbsolute()) {
            throw new IllegalArgumentException("ohsome.v2.base-url must be an absolute URI");
        }
        if (cachePath == null) {
            throw new IllegalArgumentException("ohsome.v2.cache-path must be set");
        }
        if (!Double.isFinite(gridSizeDegrees) || gridSizeDegrees <= 0) {
            throw new IllegalArgumentException("ohsome.v2.grid-size-degrees must be finite and positive");
        }
        if (!Double.isFinite(bufferDegrees) || bufferDegrees < 0) {
            throw new IllegalArgumentException("ohsome.v2.buffer-degrees must be finite and non-negative");
        }
        if (clip) {
            throw new IllegalArgumentException("ohsome.v2.clip must be false to preserve full ways at tile boundaries");
        }
        if (filter == null || filter.isBlank()) {
            throw new IllegalArgumentException("ohsome.v2.filter must not be blank");
        }
        requireNonNegative(downloadInterval, "ohsome.v2.download-interval");
        if (maxRequestsPerDay <= 0) {
            throw new IllegalArgumentException("ohsome.v2.max-requests-per-day must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("ohsome.v2.max-retries must be non-negative");
        }
        requireNonNegative(initialRetryDelay, "ohsome.v2.initial-retry-delay");
        requirePositive(maxRetryDelay, "ohsome.v2.max-retry-delay");
        requirePositive(connectTimeout, "ohsome.v2.connect-timeout");
        requirePositive(requestTimeout, "ohsome.v2.request-timeout");
        if (initialRetryDelay.compareTo(maxRetryDelay) > 0) {
            throw new IllegalArgumentException("ohsome.v2.initial-retry-delay must not exceed max-retry-delay");
        }
    }

    public URI extractionUri() {
        String value = baseUrl.toString();
        return URI.create((value.endsWith("/") ? value : value + "/") + "extraction/features.parquet");
    }

    private static void requirePositive(Duration value, String propertyName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
    }

    private static void requireNonNegative(Duration value, String propertyName) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be non-negative");
        }
    }

    public URI getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    public Path getCachePath() {
        return cachePath;
    }

    public void setCachePath(Path cachePath) {
        this.cachePath = cachePath;
    }

    public double getGridSizeDegrees() { return gridSizeDegrees; }

    public void setGridSizeDegrees(double gridSizeDegrees) { this.gridSizeDegrees = gridSizeDegrees; }

    public double getBufferDegrees() { return bufferDegrees; }

    public void setBufferDegrees(double bufferDegrees) { this.bufferDegrees = bufferDegrees; }

    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = filter;
    }

    public boolean isClip() {
        return clip;
    }

    public void setClip(boolean clip) {
        this.clip = clip;
    }

    public Duration getDownloadInterval() {
        return downloadInterval;
    }

    public void setDownloadInterval(Duration downloadInterval) {
        this.downloadInterval = downloadInterval;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public int getMaxRequestsPerDay() { return maxRequestsPerDay; }

    public void setMaxRequestsPerDay(int maxRequestsPerDay) { this.maxRequestsPerDay = maxRequestsPerDay; }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Duration getInitialRetryDelay() {
        return initialRetryDelay;
    }

    public void setInitialRetryDelay(Duration initialRetryDelay) {
        this.initialRetryDelay = initialRetryDelay;
    }

    public Duration getMaxRetryDelay() {
        return maxRetryDelay;
    }

    public void setMaxRetryDelay(Duration maxRetryDelay) {
        this.maxRetryDelay = maxRetryDelay;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }
}
