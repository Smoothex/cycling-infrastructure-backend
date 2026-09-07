package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import java.time.YearMonth;
import java.util.List;

/** A bounded set of segment/month pairs belonging to one geographic tile. */
public record OhsomeClaim(OhsomeTile tile, YearMonth month, List<OhsomeWorkItem> items) {
    public OhsomeClaim {
        items = List.copyOf(items);
    }
}
