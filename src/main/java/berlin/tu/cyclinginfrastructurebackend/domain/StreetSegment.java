package berlin.tu.cyclinginfrastructurebackend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.locationtech.jts.geom.LineString;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "street_segments")
@Getter
@Setter
@NoArgsConstructor
public class StreetSegment {

    @Id
    private Long id; // GraphHopper edge id

    private String streetName;

    @Column(columnDefinition = "geometry(LineString, 4326)")
    private LineString geometry;

    private int usageCount = 0;
    private int avoidanceCount = 0;

    /** avoidance / (avoidance + usage). Null until first observation. */
    private Double avoidanceRatio;

    @OneToMany(mappedBy = "segment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SegmentAvoidance> avoidances = new ArrayList<>();

    @OneToMany(mappedBy = "segment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SegmentExternalFactor> externalFactors = new ArrayList<>();

    @Column(name = "surface")
    private String surface;

    public void incrementUsage() {
        this.usageCount++;
        recalculateAvoidanceRatio();
    }

    public void incrementAvoidance() {
        this.avoidanceCount++;
        recalculateAvoidanceRatio();
    }

    private void recalculateAvoidanceRatio() {
        int total = usageCount + avoidanceCount;
        this.avoidanceRatio = total > 0 ? (double) avoidanceCount / total : null;
    }
}
