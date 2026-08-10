package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

public class OhsomeSnapshotCacheException extends RuntimeException {

    public enum FailureKind {
        CONFIGURATION,
        ACCESS_DENIED,
        INVALID_REQUEST,
        RATE_LIMITED,
        REMOTE_FAILURE,
        LOCAL_IO,
        INVALID_SNAPSHOT,
        INTERRUPTED
    }

    private final FailureKind failureKind;

    public OhsomeSnapshotCacheException(FailureKind failureKind, String message) {
        super(message);
        this.failureKind = failureKind;
    }

    public OhsomeSnapshotCacheException(FailureKind failureKind, String message, Throwable cause) {
        super(message, cause);
        this.failureKind = failureKind;
    }

    public FailureKind failureKind() {
        return failureKind;
    }
}
