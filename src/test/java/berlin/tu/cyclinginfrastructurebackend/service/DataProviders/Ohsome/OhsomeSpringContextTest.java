package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class OhsomeSpringContextTest {

    @Test
    void springWiresTheCacheToTheGeoParquetValidatorWithoutAnExternalObjectMapperBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(
                    OhsomeV2Properties.class,
                    OhsomeGeoParquetReader.class,
                    OhsomeSnapshotCache.class
            );

            context.refresh();

            assertThat(context.getBean(OhsomeSnapshotCache.class)).isNotNull();
            assertThat(context.getBean(OhsomeSnapshotValidator.class))
                    .isSameAs(context.getBean(OhsomeGeoParquetReader.class));
        }
    }
}
