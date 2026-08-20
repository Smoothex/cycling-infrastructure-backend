package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo;

/** Signals a transient Open-Meteo server, timeout, or I/O failure. */
public class OpenMeteoRetryableException extends RuntimeException {

    public OpenMeteoRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
