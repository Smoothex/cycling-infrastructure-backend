package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the immutable monthly ohsome v2 snapshot dataset.
 *
 * <p>The defaults deliberately describe one versioned dataset. Changing its
 * temporal range, AOI, filter, or clipping behavior makes it a different
 * dataset and is detected through the on-disk manifest.</p>
 */
@Component
@ConfigurationProperties(prefix = "ohsome.v2")
public class OhsomeV2Properties implements InitializingBean {

    private URI baseUrl = URI.create("https://api.heigit.org/ohsome-api-staging/v2");
    private String apiKey = "";
    private Path cachePath = Path.of("./data/ohsome/v2/berlin-10km-monthly-v1");
    private String startMonth = "2019-01";
    private String endMonth = "2025-01";
    private List<Double> bbox = new ArrayList<>(List.of(12.94, 52.24, 13.91, 52.77));
    private String filter = "type:way and highway=*";
    private boolean clip = false;
    private Duration downloadInterval = Duration.ofSeconds(60);
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
        YearMonth start = parseMonth(startMonth, "ohsome.v2.start-month");
        YearMonth end = parseMonth(endMonth, "ohsome.v2.end-month");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("ohsome.v2.end-month must not precede start-month");
        }
        if (bbox == null || bbox.size() != 4 || bbox.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("ohsome.v2.bbox must contain xmin,ymin,xmax,ymax");
        }
        double xmin = bbox.get(0);
        double ymin = bbox.get(1);
        double xmax = bbox.get(2);
        double ymax = bbox.get(3);
        if (xmin < -180 || xmax > 180 || ymin < -90 || ymax > 90 || xmin >= xmax || ymin >= ymax) {
            throw new IllegalArgumentException("ohsome.v2.bbox is not a valid WGS84 bounding box");
        }
        if (filter == null || filter.isBlank()) {
            throw new IllegalArgumentException("ohsome.v2.filter must not be blank");
        }
        requireNonNegative(downloadInterval, "ohsome.v2.download-interval");
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

    public YearMonth startMonthValue() {
        return parseMonth(startMonth, "ohsome.v2.start-month");
    }

    public YearMonth endMonthValue() {
        return parseMonth(endMonth, "ohsome.v2.end-month");
    }

    public List<YearMonth> months() {
        YearMonth current = startMonthValue();
        YearMonth end = endMonthValue();
        List<YearMonth> result = new ArrayList<>();
        while (!current.isAfter(end)) {
            result.add(current);
            current = current.plusMonths(1);
        }
        return List.copyOf(result);
    }

    public boolean supports(YearMonth month) {
        return month != null && !month.isBefore(startMonthValue()) && !month.isAfter(endMonthValue());
    }

    public boolean containsCoordinate(double longitude, double latitude) {
        return longitude >= bbox.get(0) && longitude <= bbox.get(2)
                && latitude >= bbox.get(1) && latitude <= bbox.get(3);
    }

    public URI extractionUri() {
        String value = baseUrl.toString();
        return URI.create((value.endsWith("/") ? value : value + "/") + "extraction/features.parquet");
    }

    private static YearMonth parseMonth(String value, String propertyName) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new IllegalArgumentException(propertyName + " must use yyyy-MM", exception);
        }
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

    public String getStartMonth() {
        return startMonth;
    }

    public void setStartMonth(String startMonth) {
        this.startMonth = startMonth;
    }

    public String getEndMonth() {
        return endMonth;
    }

    public void setEndMonth(String endMonth) {
        this.endMonth = endMonth;
    }

    public List<Double> getBbox() {
        return List.copyOf(bbox);
    }

    public void setBbox(List<Double> bbox) {
        this.bbox = bbox == null ? null : new ArrayList<>(bbox);
    }

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
