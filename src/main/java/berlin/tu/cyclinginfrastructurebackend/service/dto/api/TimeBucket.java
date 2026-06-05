package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

public enum TimeBucket {
    DAY("day"),
    WEEK("week"),
    MONTH("month");

    private final String postgresDateTruncValue;

    TimeBucket(String postgresDateTruncValue) {
        this.postgresDateTruncValue = postgresDateTruncValue;
    }

    public String getPostgresDateTruncValue() {
        return postgresDateTruncValue;
    }
}
