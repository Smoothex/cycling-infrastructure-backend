package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import java.time.YearMonth;
import java.time.ZoneOffset;

/** A distinct GraphHopper segment observed during one UTC snapshot month. */
public record OhsomeWorkItem(long segmentId, YearMonth month) {

    public long monthStartMillis() {
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    public long monthEndMillis() {
        return month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
