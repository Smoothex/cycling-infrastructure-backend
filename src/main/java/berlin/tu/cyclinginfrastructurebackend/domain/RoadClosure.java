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
 * One entry of the VIZ Berlin Baustellen/Sperrungen feed. The feed is a live
 * snapshot, so this table accumulates history: imports upsert by {@link #feedId}
 * and rows that disappear from later feed versions are kept.
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

    /** The feed's own identifier (properties.id, e.g. "8/2025"). */
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

    /** Full feed geometry: usually a GeometryCollection of one label Point plus affected-stretch LineStrings. */
    @Column(columnDefinition = "geometry(Geometry, 4326)")
    private Geometry geometry;

    /** Feed modification timestamp (properties.tstore), epoch ms. */
    private Long tstore;

    private Long firstSeenAt;

    private Long lastSeenAt;
}
