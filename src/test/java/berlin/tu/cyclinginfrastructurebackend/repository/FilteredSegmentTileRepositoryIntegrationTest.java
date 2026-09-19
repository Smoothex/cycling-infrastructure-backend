package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEnrichmentFilter;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficCondition;
import berlin.tu.cyclinginfrastructurebackend.service.dto.SegmentTileFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class FilteredSegmentTileRepositoryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName
            .parse("postgis/postgis:17-3.4").asCompatibleSubstituteFor("postgres"));
    private JdbcTemplate jdbc;
    private FilteredSegmentTileRepository repository;

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new FilteredSegmentTileRepository(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS segment_events, street_segments, segment_external_factors");
        jdbc.execute("CREATE TABLE street_segments (id bigint PRIMARY KEY, geometry geometry(LineString,4326), usage_count int, avoidance_count int, preference_count int)");
        jdbc.execute("""
                CREATE TABLE segment_events (segment_id bigint, event_timestamp bigint, ride_intent text,
                    traffic_condition text, weather_enriched boolean, ohsome_enriched boolean,
                    traffic_enriched boolean, traffic_enrichment_status text)
                """);
        jdbc.execute("CREATE TABLE segment_external_factors (segment_id bigint, source text, factor_type text, valid_from bigint, valid_to bigint)");
        jdbc.execute("CREATE INDEX ON street_segments USING gist(geometry)");
        jdbc.execute("CREATE INDEX ON segment_events(segment_id,event_timestamp)");
    }

    @Test
    void combinesConditionsOnOneEventAndKeepsAllTimeCounts() {
        jdbc.execute("""
                INSERT INTO street_segments
                SELECT id, ST_GeomFromText('LINESTRING(13.412 52.507,13.414 52.508)',4326), 198,9,29
                FROM generate_series(1,4) id
                """);
        jdbc.execute("""
                INSERT INTO segment_events VALUES
                    (1,150,'COMMUTE','CONGESTED',true,true,true,'ENRICHED'),
                    (2,150,'COMMUTE','CONGESTED',true,false,true,'ENRICHED'),
                    (2,150,'COMMUTE','CONGESTED',false,true,true,'ENRICHED'),
                    (3,250,'COMMUTE','CONGESTED',true,true,true,'ENRICHED'),
                    (4,150,'LEISURE','CONGESTED',true,true,true,'ENRICHED')
                """);
        jdbc.execute("INSERT INTO segment_external_factors VALUES (1,'berlin-open-data','ROAD_CLOSURE',100,200)");
        var filter = new SegmentTileFilter(100,200,RideIntent.COMMUTE,TrafficCondition.CONGESTED,
                Set.of(SegmentEnrichmentFilter.WEATHER_ENRICHED,SegmentEnrichmentFilter.OHSOME_ENRICHED,
                        SegmentEnrichmentFilter.TRAFFIC_ENRICHED,SegmentEnrichmentFilter.TRAFFIC_MEASURED,
                        SegmentEnrichmentFilter.ROAD_DISRUPTION_AFFECTED));
        assertThat(features(repository.tile(14,8802,5374,filter))).containsExactly(Map.of(
                "id",1L,"avoidanceCount",9L,"preferenceCount",29L,"eventCount",38L,"bucket","PREFERENCE"));
        var withoutDisruption = new SegmentTileFilter(100,200,RideIntent.COMMUTE,TrafficCondition.CONGESTED,
                Set.of(SegmentEnrichmentFilter.WEATHER_ENRICHED,SegmentEnrichmentFilter.OHSOME_ENRICHED));
        assertThat(features(repository.tile(14,8802,5374,withoutDisruption))).hasSize(1);
        jdbc.execute("UPDATE segment_external_factors SET valid_to=149");
        assertThat(repository.tile(14,8802,5374,filter)).isEmpty();
        assertThat(repository.tile(14,8803,5374,withoutDisruption)).isEmpty();
    }

    @Test
    void preservesEveryMatchingSegmentAtOverviewAndDetailZooms() {
        jdbc.execute("""
                INSERT INTO street_segments
                SELECT id, ST_GeomFromText('LINESTRING(13.412 52.507,13.414 52.508)',4326), 1,0,1
                FROM generate_series(1,10001) id
                """);
        jdbc.execute("INSERT INTO segment_events SELECT id,150,'COMMUTE','UNKNOWN',true,true,false,'NO_DATA' FROM street_segments");
        var filter = new SegmentTileFilter(0,Long.MAX_VALUE,null,null,Set.of(SegmentEnrichmentFilter.OHSOME_ENRICHED));
        // Dense neighboring segments must not disappear when zooming out.
        for (int z : new int[]{10, 12, 13, 14}) {
            int shift = 14 - z;
            assertThat(features(repository.tile(z,8802 >> shift,5374 >> shift,filter))).hasSize(10001);
        }
        jdbc.execute("UPDATE segment_events SET ohsome_enriched=false WHERE segment_id=10001");
        var overview = features(repository.tile(10,550,335,filter));
        assertThat(overview).hasSize(10000);
        assertThat(overview).noneMatch(feature -> feature.get("id").equals(10001L));
    }

    // Minimal MVT protobuf reader: assert the actual emitted feature properties without adding a runtime dependency.
    private static List<Map<String,Object>> features(byte[] tile) {
        List<Map<String,Object>> result = new ArrayList<>();
        for (Field layerField : fields(tile)) {
            if (layerField.number != 3) continue;
            var layer = fields(layerField.bytes);
            var keys = layer.stream().filter(f -> f.number == 3).map(f -> new String(f.bytes,StandardCharsets.UTF_8)).toList();
            var values = layer.stream().filter(f -> f.number == 4).map(f -> {
                Field value = fields(f.bytes).getFirst();
                return value.number == 1 ? (Object)new String(value.bytes,StandardCharsets.UTF_8) : value.value;
            }).toList();
            for (Field feature : layer) {
                if (feature.number != 2) continue;
                Map<String,Object> properties = new HashMap<>();
                for (Field part : fields(feature.bytes)) {
                    if (part.number != 2) continue;
                    ByteBuffer tags = ByteBuffer.wrap(part.bytes);
                    while (tags.hasRemaining()) properties.put(keys.get((int)varint(tags)),values.get((int)varint(tags)));
                }
                result.add(properties);
            }
        }
        return result;
    }

    private record Field(int number, byte[] bytes, long value) {}
    private static List<Field> fields(byte[] bytes) {
        List<Field> fields = new ArrayList<>();
        ByteBuffer input = ByteBuffer.wrap(bytes);
        while (input.hasRemaining()) {
            int tag = (int)varint(input);
            int wire = tag & 7;
            if (wire == 2) {
                byte[] value = new byte[(int)varint(input)];
                input.get(value);
                fields.add(new Field(tag >>> 3,value,0));
            } else if (wire == 0) {
                fields.add(new Field(tag >>> 3,null,varint(input)));
            } else {
                throw new AssertionError("Unexpected wire type " + wire);
            }
        }
        return fields;
    }
    private static long varint(ByteBuffer input) {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            byte b = input.get();
            result |= (long)(b & 127) << shift;
            if ((b & 128) == 0) return result;
        }
        throw new AssertionError("Invalid varint");
    }
}
