package berlin.tu.cyclinginfrastructurebackend.domain;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ExternalFactorType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Geometry;
import java.util.Map;
import java.util.UUID;
/**
 * Stores external factors (weather, construction, closures) that may explain
 * why a street segment is avoided. Used valid_from/valid_to so temporal overlap
 * with SegmentAvoidance records can be computed.
 * <p>
 * The metadata column (JSONB) holds source-specific details without requiring
 * schema changes per external factor type.
 */
@Entity
@Table(name = "segment_external_factors", indexes = {
        @Index(name = "idx_ext_factor_segment", columnList = "segment_id"),
        @Index(name = "idx_ext_factor_type", columnList = "factorType"),
        @Index(name = "idx_ext_factor_valid_range", columnList = "validFrom, validTo")
})
@Getter
@Setter
@NoArgsConstructor
public class SegmentExternalFactor {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "segment_id", nullable = false)
    private StreetSegment segment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExternalFactorType factorType;

    /** Where this data came from (e.g., "berlin-open-data", "open-meteo") */
    @Column(nullable = false)
    private String source;

    @Column(nullable = false)
    private Long validFrom;

    private Long validTo;

    /** Optional spatial extent of the factor (e.g., construction site polygon) */
    @Column(columnDefinition = "geometry(Geometry, 4326)")
    private Geometry affectedArea;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;
}
