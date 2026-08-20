package berlin.tu.cyclinginfrastructurebackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/** Composite key for one normalized Open-Meteo grid/hour cache row. */
@Embeddable
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class OpenMeteoHourlyWeatherId implements Serializable {

    @Column(name = "latitude_tenths", nullable = false)
    private Integer latitudeTenths;

    @Column(name = "longitude_tenths", nullable = false)
    private Integer longitudeTenths;

    @Column(name = "valid_from", nullable = false)
    private Long validFrom;
}
