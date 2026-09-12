package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.RoadClosures;

import berlin.tu.cyclinginfrastructurebackend.domain.RoadClosure;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ExternalFactorType;
import berlin.tu.cyclinginfrastructurebackend.repository.RoadClosureRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentExternalFactorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

class RoadClosureImportServiceTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @CsvSource({"true,true,false", "true,false,true", "false,true,true"})
    void disabledPipelineSkipsStartupImportIndexAndRefresh(boolean pipeline, boolean enrichment, boolean viz) {
        assertDisabled(
                "pipeline.enabled=" + pipeline,
                "pipeline.enrichment.enabled=" + enrichment,
                "pipeline.enrichment.berlin-open-data.enabled=" + viz);
    }

    @Test
    void missingProviderSwitchDefaultsToDisabled() {
        assertDisabled();
    }

    private void assertDisabled(String... properties) {
        RoadClosureRepository repository = mock(RoadClosureRepository.class);
        RestClient client = mock(RestClient.class);
        contextRunner(repository, client).withPropertyValues(properties).run(context -> {
            assertThat(context).hasNotFailed();
            RoadClosureImportService importer = context.getBean(RoadClosureImportService.class);
            assertThat(importer.isEnabled()).isFalse();
            assertThat(importer.ensureImported()).isFalse();
            importer.refresh();

            // Includes the provider's actual @PostConstruct call during context startup.
            verifyNoInteractions(repository, client);
            assertThat(tempDir.resolve("cache/closures.json")).doesNotExist();
        });
    }

    @Test
    void enabledProviderStillImportsBuildsIndexAndRefreshes() {
        RoadClosureRepository repository = mock(RoadClosureRepository.class);
        when(repository.existsByFeedIdStartingWith("historical:")).thenReturn(true);
        when(repository.count()).thenReturn(1L);
        RestClient client = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(client.get().uri("https://example.invalid/closures.json").retrieve().body(byte[].class))
                .thenReturn("{\"features\":[]}".getBytes(StandardCharsets.UTF_8));
        org.mockito.Mockito.clearInvocations(client);

        contextRunner(repository, client)
                .withPropertyValues("pipeline.enrichment.berlin-open-data.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RoadClosureImportService importer = context.getBean(RoadClosureImportService.class);
                    assertThat(importer.isEnabled()).isTrue();
                    verify(repository).findAll();
                    verify(repository).existsByFeedIdStartingWith("historical:");
                    verify(client).get();

                    importer.refresh();

                    verify(client, times(2)).get();
                    assertThat(tempDir.resolve("cache/closures.json")).exists();
                });
    }

    private ApplicationContextRunner contextRunner(RoadClosureRepository repository, RestClient client) {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(client);
        return new ApplicationContextRunner()
                .withPropertyValues(
                        "enrichment.berlin-open-data.url=https://example.invalid/closures.json",
                        "enrichment.berlin-open-data.cache-file=" + tempDir.resolve("cache/closures.json"))
                .withBean(RoadClosureRepository.class, () -> repository)
                .withBean(SegmentExternalFactorRepository.class, () -> mock(SegmentExternalFactorRepository.class))
                .withBean(RestClient.Builder.class, () -> builder)
                .withBean(RoadClosureImportService.class)
                .withBean(RoadClosureDataProvider.class);
    }

    @Test
    void normalizesLegacySnapshotsAndUsesBeendetAsTheEndTimestamp() throws Exception {
        Path historicalDirectory = tempDir.resolve("historical");
        Files.createDirectories(historicalDirectory.resolve("2024"));

        writeSnapshot("2024/incidents_viz_20241021_090001.json",
                feature("source-1", "2024-10-21T06:54:06Z", "Sperrung",
                        "21.10.2024 05:10", "21.10.2024 23:59", "Alte Straße", "gesperrt"),
                feature("ended-only", "2024-10-21T08:00:00Z", "Beendet",
                        "21.10.2024 09:00", null, "Nebenstraße", "wieder offen"));

        writeSnapshot("2024/incidents_viz_20241022_090001.json",
                feature("source-1", "2024-10-22T06:54:06Z", "Sperrung",
                        "21.10.2024 05:10", null, "Neue Straße", "weiterhin gesperrt"),
                feature("source-1", "2024-10-22T07:00:00Z", "Baustelle",
                        "01.11.2024 08:00", "01.11.2024 18:00", "Baustraße", "Bauarbeiten"));

        writeSnapshot("2024/incidents_viz_20241023_090001.json",
                feature("source-1", "2024-10-23T06:00:00Z", "Beendet",
                        "21.10.2024 05:10", null, "Neue Straße", "wieder offen"));

        RoadClosureImportService service = service();
        List<RoadClosure> closures = service.loadHistoricalClosures(historicalDirectory, 1234L)
                .stream()
                .sorted(Comparator.comparing(RoadClosure::getValidFrom))
                .toList();

        assertThat(closures).hasSize(2);

        RoadClosure endedClosure = closures.getFirst();
        assertThat(endedClosure.getFeedId()).isEqualTo(
                "historical:source-1:" + RoadClosureImportService.parseBerlinDateTime("21.10.2024 05:10"));
        assertThat(endedClosure.getStreet()).isEqualTo("Neue Straße");
        assertThat(endedClosure.getContent()).isEqualTo("weiterhin gesperrt");
        assertThat(endedClosure.getFactorType()).isEqualTo(ExternalFactorType.ROAD_CLOSURE);
        assertThat(endedClosure.getValidTo()).isEqualTo(Instant.parse("2024-10-23T06:00:00Z").toEpochMilli());

        RoadClosure recurringClosure = closures.getLast();
        assertThat(recurringClosure.getFeedId()).startsWith("historical:source-1:");
        assertThat(recurringClosure.getFactorType()).isEqualTo(ExternalFactorType.CONSTRUCTION);
        assertThat(recurringClosure.getStreet()).isEqualTo("Baustraße");
    }

    @Test
    void parsesConfiguredPrivateArchive() throws Exception {
        String snapshots2024 = System.getProperty("vizHistorical2024");
        String snapshots2025 = System.getProperty("vizHistorical2025");
        assumeTrue(snapshots2024 != null && snapshots2025 != null,
                "Set vizHistorical2024 and vizHistorical2025 to run the private-archive check");

        Path historicalDirectory = tempDir.resolve("configured-history");
        Files.createDirectories(historicalDirectory);
        Files.createSymbolicLink(historicalDirectory.resolve("2024"), Path.of(snapshots2024));
        Files.createSymbolicLink(historicalDirectory.resolve("2025"), Path.of(snapshots2025));

        List<RoadClosure> closures = service().loadHistoricalClosures(historicalDirectory, 1234L);

        assertThat(closures.size()).isEqualTo(8_775);
    }

    @Test
    void skipsStaticArchiveWhenHistoricalClosuresAreAlreadyStored() {
        RoadClosureRepository repository = mock(RoadClosureRepository.class);
        when(repository.existsByFeedIdStartingWith("historical:")).thenReturn(true);

        service(repository).importHistoricalSnapshots(tempDir.resolve("historical"));

        verify(repository).existsByFeedIdStartingWith("historical:");
        verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.any());
    }

    private RoadClosureImportService service() {
        return service(mock(RoadClosureRepository.class));
    }

    private RoadClosureImportService service(RoadClosureRepository repository) {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(mock(RestClient.class));
        return new RoadClosureImportService(
                repository,
                builder,
                "https://example.invalid/closures.json",
                tempDir.resolve("cache/closures.json").toString()
        );
    }

    private void writeSnapshot(String relativePath, String... features) throws Exception {
        Path path = tempDir.resolve("historical").resolve(relativePath);
        Files.createDirectories(path.getParent());
        String json = """
                {"type":"FeatureCollection","name":"Baustellen","features":[%s]}
                """.formatted(String.join(",", features));
        Files.writeString(path, json, StandardCharsets.ISO_8859_1);
    }

    private static String feature(String id,
                                  String tstore,
                                  String subtype,
                                  String validFrom,
                                  String validTo,
                                  String street,
                                  String content) {
        String validToJson = validTo == null ? "null" : "\"" + validTo + "\"";
        return """
                {"type":"Feature","properties":{
                  "id":"%s","tstore":"%s","objectState":"modified","subtype":"%s",
                  "severity":"keine Sperrung","validity":{"from":"%s","to":%s},
                  "direction":"Beidseitig","street":"%s","content":"%s"
                },"geometry":{"type":"Point","coordinates":[13.4,52.5]}}
                """.formatted(id, tstore, subtype, validFrom, validToJson, street, content);
    }
}
