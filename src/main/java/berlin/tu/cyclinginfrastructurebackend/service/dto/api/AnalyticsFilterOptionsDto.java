package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

import java.util.List;

public record AnalyticsFilterOptionsDto(List<String> rideIntents, List<String> trafficConditions) {
}
