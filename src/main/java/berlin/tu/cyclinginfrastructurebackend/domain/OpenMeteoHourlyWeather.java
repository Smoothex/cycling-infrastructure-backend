package berlin.tu.cyclinginfrastructurebackend.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Normalized, restart-safe Open-Meteo weather cache for one grid location and UTC hour. */
@Entity
@Table(name = "open_meteo_hourly_weather")
@Getter
@Setter
@NoArgsConstructor
public class OpenMeteoHourlyWeather {

    @EmbeddedId
    private OpenMeteoHourlyWeatherId id;

    private Double temperature2m;
    private Double precipitation;
    private Double windSpeed10m;
    private Double windDirection10m;
    private Integer weatherCode;
}
