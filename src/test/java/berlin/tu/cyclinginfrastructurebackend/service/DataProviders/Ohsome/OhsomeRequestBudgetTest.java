package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OhsomeRequestBudgetTest {
    private static final Instant NOW = Instant.parse("2026-09-19T06:00:00Z");
    @TempDir Path directory;

    @Test
    void rollingBudgetSurvivesRestartAndReleasesSlotsIndividually() {
        var first = budget(NOW, 2);
        first.reserve();
        budget(NOW.plusSeconds(60), 2).reserve();
        var restarted = budget(NOW.plusSeconds(120), 2);
        assertThat(restarted.resumeAt()).contains(NOW.plus(Duration.ofHours(24)));
        assertThatThrownBy(restarted::reserve).hasMessageContaining("automatic retry");

        var tomorrow = budget(NOW.plus(Duration.ofHours(24)), 2);
        assertThat(tomorrow.resumeAt()).isEmpty();
        tomorrow.reserve();
        assertThat(tomorrow.resumeAt()).contains(NOW.plus(Duration.ofHours(24)).plusSeconds(60));
    }

    @Test
    void quotaWithoutResetHeaderPersistsTwentyFourHourPause() {
        var budget = budget(NOW, 250);
        budget.reserve();
        assertThat(budget.quotaExceeded(Optional.empty())).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(budget(NOW.plusSeconds(60), 250).resumeAt()).isPresent();
        assertThat(budget(NOW.plus(Duration.ofHours(24)), 250).resumeAt()).isEmpty();
    }

    @Test
    void providerRetryAfterCanDelayResumeBeyondLocalBudgetWindow() {
        var budget = budget(NOW, 1);
        budget.reserve();
        budget.quotaExceeded(Optional.of(Duration.ofHours(30)));
        assertThat(budget(NOW.plus(Duration.ofHours(24)), 1).resumeAt())
                .contains(NOW.plus(Duration.ofHours(30)));
        assertThat(budget(NOW.plus(Duration.ofHours(30)), 1).resumeAt()).isEmpty();
    }

    @Test
    void shortProviderRetryAfterDoesNotBypassLocalBudget() {
        var budget = budget(NOW, 1);
        budget.reserve();
        assertThat(budget.quotaExceeded(Optional.of(Duration.ofHours(1))))
                .isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void malformedPersistedStateFailsClosed() throws Exception {
        Files.writeString(directory.resolve("request-budget.json"), "broken");
        assertThatThrownBy(() -> budget(NOW, 250).reserve())
                .isInstanceOf(OhsomeSnapshotCacheException.class)
                .hasMessageContaining("Could not read ohsome request budget");
    }

    private OhsomeRequestBudget budget(Instant now, int limit) {
        var properties = new OhsomeV2Properties();
        properties.setCachePath(directory);
        properties.setMaxRequestsPerDay(limit);
        return new OhsomeRequestBudget(properties, Clock.fixed(now, ZoneOffset.UTC), new ObjectMapper());
    }
}
