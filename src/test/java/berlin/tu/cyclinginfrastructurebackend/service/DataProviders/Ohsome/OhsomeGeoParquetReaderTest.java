package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OhsomeGeoParquetReaderTest {

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"ohsome-v2-zstd-geoparquet.parquet.b64", "ohsome-v2-current-schema.parquet.b64"})
    void readsCurrentAndLegacySchemaWithZstdMapAndWkbAndIgnoresPolygonRows(String fixture) throws Exception {
        // Current API: tags / "ohsome API"; legacy snapshots: osm_tags / api.
        Path snapshot = decodeFixture(fixture);
        OhsomeGeoParquetReader reader = new OhsomeGeoParquetReader();

        OhsomeSnapshotMetadata metadata = reader.validate(snapshot);
        List<OhsomeFeature> features = reader.readFeatures(snapshot);

        assertThat(metadata).isEqualTo(new OhsomeSnapshotMetadata(
                3, "2.0-test", "1.1.0", "EPSG:4326"));
        assertThat(features).hasSize(2);
        assertThat(features).allSatisfy(feature -> {
            assertThat(feature.geometry().getSRID()).isEqualTo(4326);
            assertThat(feature.geometry().isEmpty()).isFalse();
        });
        assertThat(features)
                .anySatisfy(feature -> assertThat(feature.tags())
                        .containsEntry("highway", "footway")
                        .containsEntry("surface", "compacted"));
        assertThat(reader.read(snapshot).featureCount()).isEqualTo(2);
    }

    @Test
    void rejectsCorruptFilesBeforeTheyCanEnterTheSnapshotCache() throws Exception {
        Path snapshot = temporaryDirectory.resolve("corrupt.parquet");
        Files.writeString(snapshot, "not a parquet file");

        assertThatThrownBy(() -> new OhsomeGeoParquetReader().validate(snapshot))
                .isInstanceOf(IOException.class);
    }

    private Path decodeFixture(String fixture) throws Exception {
        Path snapshot = temporaryDirectory.resolve("snapshot.parquet");
        try (InputStream encoded = getClass().getResourceAsStream(
                "/ohsome/" + fixture)) {
            assertThat(encoded).isNotNull();
            Files.write(snapshot, Base64.getMimeDecoder().decode(encoded.readAllBytes()));
        }
        return snapshot;
    }
}
