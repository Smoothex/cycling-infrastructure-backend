package berlin.tu.cyclinginfrastructurebackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.locationtech.jts.geom.LineString;

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

    public void incrementUsage() {
        this.usageCount++;
    }
}

