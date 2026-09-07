package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import java.math.BigDecimal;
import java.util.List;

/** Grid indices use floor(longitude / size), floor(latitude / size), with origin (0, 0). */
public record OhsomeTile(int x, int y) {
    public String id() { return x + "_" + y; }

    public List<Double> bounds(OhsomeV2Properties properties) {
        BigDecimal size = BigDecimal.valueOf(properties.getGridSizeDegrees());
        BigDecimal buffer = BigDecimal.valueOf(properties.getBufferDegrees());
        return List.of(
                Math.max(-180, size.multiply(BigDecimal.valueOf(x)).subtract(buffer).doubleValue()),
                Math.max(-90, size.multiply(BigDecimal.valueOf(y)).subtract(buffer).doubleValue()),
                Math.min(180, size.multiply(BigDecimal.valueOf((long) x + 1)).add(buffer).doubleValue()),
                Math.min(90, size.multiply(BigDecimal.valueOf((long) y + 1)).add(buffer).doubleValue()));
    }
}
