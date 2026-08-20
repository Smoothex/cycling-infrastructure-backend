package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Configuration for bulk Open-Meteo archive enrichment. */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "pipeline.enrichment.weather")
public class OpenMeteoProperties implements InitializingBean {

    private int batchSize = 5;
    private Duration initialRetryDelay = Duration.ofMinutes(1);
    private Duration maxRetryDelay = Duration.ofMinutes(30);
    private Duration connectTimeout = Duration.ofSeconds(30);
    private Duration requestTimeout = Duration.ofMinutes(2);

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        if (batchSize < 1 || batchSize > 5) {
            throw new IllegalArgumentException("pipeline.enrichment.weather.batch-size must be between 1 and 5");
        }
        requirePositive(initialRetryDelay, "pipeline.enrichment.weather.initial-retry-delay");
        requirePositive(maxRetryDelay, "pipeline.enrichment.weather.max-retry-delay");
        requirePositive(connectTimeout, "pipeline.enrichment.weather.connect-timeout");
        requirePositive(requestTimeout, "pipeline.enrichment.weather.request-timeout");
        if (initialRetryDelay.compareTo(maxRetryDelay) > 0) {
            throw new IllegalArgumentException(
                    "pipeline.enrichment.weather.initial-retry-delay must not exceed max-retry-delay");
        }
    }

    private static void requirePositive(Duration value, String propertyName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
    }

}
