package berlin.tu.cyclinginfrastructurebackend.domain;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.ExternalFactorType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RoadClosureSeverity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.locationtech.jts.geom.Geometry;

import java.util.UUID;

/**
 * One normalized occurrence from the historical or live VIZ Berlin
 * Baustellen/Sperrungen data. Historical rows are inserted from the private
 * snapshot archive; live rows retain their feed-id upsert behavior.
 */
@Entity
@Table(name = "road_closures")
@Getter
@Setter
@NoArgsConstructor
public class RoadClosure {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Live feed id, or a derived id for one historical source-id/valid-from occurrence. */
    @Column(unique = true, nullable = false)
    private String feedId;

    private String lmsId;

    @Enumerated(EnumType.STRING)
    private ExternalFactorType factorType;

    @Enumerated(EnumType.STRING)
    private RoadClosureSeverity severity;

    private String direction;

    private String street;

    @Column(columnDefinition = "text")
    private String section;

    @Column(columnDefinition = "text")
    private String content;

    /** Validity bounds in epoch ms (parsed from Berlin-local datetimes); validTo may be open-ended. */
    private Long validFrom;

    private Long validTo;

    /** Full feed geometry: GeometryCollection in the live feed; MultiLineString or Point in legacy snapshots. */
    @Column(columnDefinition = "geometry(Geometry, 4326)")
    private Geometry geometry;

    /** Feed modification timestamp (properties.tstore), epoch ms. */
    private Long tstore;

    private Long firstSeenAt;

    private Long lastSeenAt;
}
