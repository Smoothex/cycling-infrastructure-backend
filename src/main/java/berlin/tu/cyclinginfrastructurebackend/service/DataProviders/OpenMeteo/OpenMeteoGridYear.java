package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo;

import java.time.LocalDate;
import java.time.ZoneOffset;

/** Claimed Open-Meteo grid location for one UTC calendar year. */
public record OpenMeteoGridYear(int latitudeTenths, int longitudeTenths, int year) {

    public OpenMeteoLocation location() {
        return new OpenMeteoLocation(latitudeTenths, longitudeTenths);
    }

    public long yearStartMillis() {
        return LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    public long nextYearStartMillis() {
        return LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
