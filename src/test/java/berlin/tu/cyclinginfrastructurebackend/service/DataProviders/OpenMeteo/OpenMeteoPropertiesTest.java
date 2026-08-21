package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenMeteoPropertiesTest {

    @Test
    void defaultsToABoundedEventBatch() {
        OpenMeteoProperties properties = new OpenMeteoProperties();

        properties.validate();

        assertThat(properties.getEventBatchSize()).isEqualTo(25_000);
    }

    @Test
    void rejectsEventBatchSizesOutsideTheSupportedRange() {
        OpenMeteoProperties properties = new OpenMeteoProperties();

        properties.setEventBatchSize(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event-batch-size");

        properties.setEventBatchSize(100_001);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event-batch-size");
    }
}
