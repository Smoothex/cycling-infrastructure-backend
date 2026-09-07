package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.LocalReadOptions;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Local, Hadoop-free reader for the GeoParquet files returned by ohsome v2.
 * Only the four columns needed by matching are materialized.
 */
@Component
public class OhsomeGeoParquetReader implements OhsomeSnapshotValidator {

    private final ObjectMapper objectMapper;

    public OhsomeGeoParquetReader() {
        this(new ObjectMapper());
    }

    OhsomeGeoParquetReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Loads one monthly file and builds its one in-memory spatial index. */
    public OhsomeSnapshot read(Path snapshot) throws IOException {
        return new OhsomeSnapshot(readFeatures(snapshot));
    }

    List<OhsomeFeature> readFeatures(Path snapshot) throws IOException {
        List<OhsomeFeature> features = new ArrayList<>();
        scan(snapshot, features::add);
        return List.copyOf(features);
    }

    /**
     * Verifies metadata, schema and every compressed row group. A successful validation therefore
     * also proves that ZSTD data and the nested MAP/WKB payloads are readable by this runtime.
     */
    @Override
    public OhsomeSnapshotMetadata validate(Path snapshot) throws IOException {
        return scan(snapshot, ignored -> { });
    }

    private OhsomeSnapshotMetadata scan(Path snapshot, Consumer<OhsomeFeature> featureConsumer)
            throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!Files.isRegularFile(snapshot) || Files.size(snapshot) == 0L) {
            throw new IOException("Ohsome snapshot is missing or empty: " + snapshot);
        }

        ParquetReadOptions readOptions = LocalReadOptions.create(new OhsomeZstdCompressionCodecFactory());
        try (ParquetFileReader reader = new ParquetFileReader(new LocalInputFile(snapshot), readOptions)) {
            ParquetMetadata footer = reader.getFooter();
            MessageType fileSchema = footer.getFileMetaData().getSchema();
            String tagsColumn = fileSchema.containsField("tags") ? "tags" : "osm_tags";
            validateSchema(fileSchema, tagsColumn);
            OhsomeSnapshotMetadata metadata = metadata(footer, reader.getRecordCount());
            MessageType requestedSchema = requestedSchema(fileSchema, tagsColumn);
            reader.setRequestedSchema(requestedSchema);

            MessageColumnIO columnIO = new ColumnIOFactory().getColumnIO(requestedSchema, fileSchema);
            WKBReader wkbReader = new WKBReader();
            long rowsRead = 0L;
            PageReadStore pages;
            while ((pages = reader.readNextRowGroup()) != null) {
                RecordReader<Group> recordReader = columnIO.getRecordReader(
                        pages, new GroupRecordConverter(requestedSchema));
                for (long row = 0; row < pages.getRowCount(); row++) {
                    Group group = recordReader.read();
                    rowsRead++;
                    OhsomeFeature feature = readFeature(group, wkbReader, snapshot, rowsRead, tagsColumn);
                    if (feature != null) {
                        featureConsumer.accept(feature);
                    }
                }
            }
            if (rowsRead != metadata.featureCount()) {
                throw new IOException("Snapshot row count does not match its Parquet footer: " + snapshot);
            }
            return metadata;
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Cannot decode ohsome GeoParquet snapshot " + snapshot, exception);
        }
    }

    private void validateSchema(MessageType schema, String tagsColumn) throws IOException {
        for (String column : requiredColumns(tagsColumn)) {
            if (!schema.containsField(column)) {
                throw new IOException("Ohsome GeoParquet is missing required column: " + column);
            }
        }

        requirePrimitive(schema, "osm_id", PrimitiveType.PrimitiveTypeName.INT64);
        requirePrimitive(schema, "geom_type", PrimitiveType.PrimitiveTypeName.BINARY);
        requirePrimitive(schema, "geom", PrimitiveType.PrimitiveTypeName.BINARY);

        Type tags = schema.getType(tagsColumn);
        if (tags.isPrimitive() || tags.getOriginalType() != org.apache.parquet.schema.OriginalType.MAP) {
            throw new IOException("Ohsome GeoParquet column " + tagsColumn + " must use Parquet MAP encoding");
        }
        if (!schema.containsPath(new String[]{tagsColumn, "key_value", "key"})
                || !schema.containsPath(new String[]{tagsColumn, "key_value", "value"})) {
            throw new IOException("Ohsome GeoParquet " + tagsColumn + " MAP has an unsupported physical layout");
        }
    }

    private void requirePrimitive(MessageType schema,
                                  String column,
                                  PrimitiveType.PrimitiveTypeName expected) throws IOException {
        Type type = schema.getType(column);
        if (!type.isPrimitive() || type.asPrimitiveType().getPrimitiveTypeName() != expected) {
            throw new IOException("Ohsome GeoParquet column " + column + " must be " + expected);
        }
    }

    private MessageType requestedSchema(MessageType fileSchema, String tagsColumn) {
        return new MessageType(
                fileSchema.getName(),
                requiredColumns(tagsColumn).stream().map(fileSchema::getType).toList()
        );
    }

    private List<String> requiredColumns(String tagsColumn) {
        return List.of("osm_id", tagsColumn, "geom_type", "geom");
    }

    private OhsomeSnapshotMetadata metadata(ParquetMetadata footer, long featureCount)
            throws IOException {
        if (featureCount <= 0L) {
            throw new IOException("Ohsome GeoParquet snapshot contains no features");
        }

        Map<String, String> keyValues = footer.getFileMetaData().getKeyValueMetaData();
        JsonNode geo = parseRequiredMetadata(keyValues, "geo");
        // Current v2 uses "ohsome API"; retain support for existing cached snapshots.
        JsonNode api = parseRequiredMetadata(keyValues, keyValues.containsKey("ohsome API") ? "ohsome API" : "api");

        String geoVersion = requiredText(geo, "version", "GeoParquet version");
        String primaryColumn = requiredText(geo, "primary_column", "GeoParquet primary column");
        if (!"geom".equals(primaryColumn)) {
            throw new IOException("Unsupported GeoParquet primary geometry column: " + primaryColumn);
        }

        JsonNode geometryMetadata = geo.path("columns").path("geom");
        if (!"WKB".equalsIgnoreCase(geometryMetadata.path("encoding").asText())) {
            throw new IOException("Ohsome GeoParquet geom column must use WKB encoding");
        }
        JsonNode crsId = geometryMetadata.path("crs").path("id");
        String authority = crsId.path("authority").asText();
        int code = crsId.path("code").asInt(Integer.MIN_VALUE);
        if (!"EPSG".equalsIgnoreCase(authority) || code != 4326) {
            throw new IOException("Ohsome GeoParquet must use EPSG:4326");
        }

        String apiVersion = requiredText(api, "version", "ohsome API version");
        return new OhsomeSnapshotMetadata(featureCount, apiVersion, geoVersion, "EPSG:4326");
    }

    private JsonNode parseRequiredMetadata(Map<String, String> values, String key) throws IOException {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Ohsome GeoParquet is missing required '" + key + "' metadata");
        }
        try {
            return objectMapper.readTree(value);
        } catch (RuntimeException exception) {
            throw new IOException("Ohsome GeoParquet contains invalid '" + key + "' metadata", exception);
        }
    }

    private String requiredText(JsonNode node, String field, String description) throws IOException {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IOException("Ohsome GeoParquet is missing " + description);
        }
        return value;
    }

    private OhsomeFeature readFeature(Group group,
                                      WKBReader wkbReader,
                                      Path snapshot,
                                      long rowNumber,
                                      String tagsColumn) throws IOException {
        if (group.getFieldRepetitionCount("geom_type") == 0
                || !"LineString".equals(group.getString("geom_type", 0))) {
            return null;
        }
        if (group.getFieldRepetitionCount("osm_id") == 0
                || group.getFieldRepetitionCount("geom") == 0) {
            throw new IOException("LineString row " + rowNumber + " has no osm_id or geom in " + snapshot);
        }

        Geometry geometry;
        try {
            geometry = wkbReader.read(group.getBinary("geom", 0).getBytes());
        } catch (ParseException exception) {
            throw new IOException("Invalid WKB in row " + rowNumber + " of " + snapshot, exception);
        }
        if (!(geometry instanceof LineString lineString)) {
            throw new IOException("geom_type and WKB geometry disagree in row " + rowNumber + " of " + snapshot);
        }
        lineString.setSRID(4326);

        return new OhsomeFeature(
                group.getLong("osm_id", 0),
                lineString,
                readTags(group, tagsColumn)
        );
    }

    private Map<String, String> readTags(Group group, String tagsColumn) {
        if (group.getFieldRepetitionCount(tagsColumn) == 0) {
            return Map.of();
        }
        Group tagsGroup = group.getGroup(tagsColumn, 0);
        int entryCount = tagsGroup.getFieldRepetitionCount("key_value");
        Map<String, String> tags = new HashMap<>(Math.max(16, entryCount * 2));
        for (int index = 0; index < entryCount; index++) {
            Group entry = tagsGroup.getGroup("key_value", index);
            if (entry.getFieldRepetitionCount("key") > 0
                    && entry.getFieldRepetitionCount("value") > 0) {
                tags.put(entry.getString("key", 0), entry.getString("value", 0));
            }
        }
        return tags;
    }
}
