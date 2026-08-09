package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.repository.TileExportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TileBuildServiceSpringContextTest {

    @TempDir
    Path tempDirectory;

    @Test
    void springInstantiatesTheProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "tile-test-properties",
                    Map.of(
                            "tiles.directory", tempDirectory.toString(),
                            "tiles.tippecanoe-binary", "tippecanoe",
                            "tiles.tile-join-binary", "tile-join",
                            "tiles.build-timeout-minutes", "30",
                            "tiles.auto-rebuild.enabled", "true",
                            "tiles.auto-rebuild.quiet-period-ms", "900000"
                    )
            ));
            context.registerBean(TileExportRepository.class, () -> mock(TileExportRepository.class));
            context.register(PipelineActivityTracker.class, TileBuildService.class);

            context.refresh();

            assertThat(context.getBean(TileBuildService.class)).isNotNull();
        }
    }
}
