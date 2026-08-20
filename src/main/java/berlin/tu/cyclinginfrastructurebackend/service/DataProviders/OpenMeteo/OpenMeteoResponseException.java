package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo;

/** Signals a non-retryable request or invalid successful response. */
public class OpenMeteoResponseException extends RuntimeException {

    public OpenMeteoResponseException(String message) {
        super(message);
    }

    public OpenMeteoResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
