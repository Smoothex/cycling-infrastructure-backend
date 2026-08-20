package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo;

/** One validated hourly cache row returned by Open-Meteo. */
public record OpenMeteoHourlyRow(
        int latitudeTenths,
        int longitudeTenths,
        long validFrom,
        Double temperature2m,
        Double precipitation,
        Double windSpeed10m,
        Double windDirection10m,
        Integer weatherCode
) {
}
