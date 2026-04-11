package berlin.tu.cyclinginfrastructurebackend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.locationtech.jts.geom.LineString;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a street segment (GraphHopper edge) in the road network with usage, avoidance,
 * and preference metrics. Each segment tracks how often it is traversed, how often it is
 * avoided, and how often it is preferred.
 */
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
    private int preferenceCount = 0;

    /** avoidance / (avoidance + usage). Null until first observation. */
    private Double avoidanceRatio;

    /** preference / (preference + usage). Null until first observation. */
    private Double preferenceRatio;

    @OneToMany(mappedBy = "segment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SegmentEvent> events = new ArrayList<>();

    @OneToMany(mappedBy = "segment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SegmentExternalFactor> externalFactors = new ArrayList<>();

    @Column(name = "surface")
    private String surface;

    public void incrementUsage() {
        this.usageCount++;
        recalculateAvoidanceRatio();
        recalculatePreferenceRatio();
    }

    public void incrementAvoidance() {
        this.avoidanceCount++;
        recalculateAvoidanceRatio();
    }

    public void incrementPreference() {
        this.preferenceCount++;
        recalculatePreferenceRatio();
    }

    private void recalculateAvoidanceRatio() {
        int total = usageCount + avoidanceCount;
        this.avoidanceRatio = total > 0 ? (double) avoidanceCount / total : null;
    }

    private void recalculatePreferenceRatio() {
        int total = usageCount + preferenceCount;
        this.preferenceRatio = total > 0 ? (double) preferenceCount / total : null;
    }
}
