package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo;

/** One fixed 0.1 degree Open-Meteo request location. */
public record OpenMeteoLocation(int latitudeTenths, int longitudeTenths) {

    public double latitude() {
        return latitudeTenths / 10.0;
    }

    public double longitude() {
        return longitudeTenths / 10.0;
    }
}
