package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

import java.util.List;

public record RouteReviewSampleDto(
        int sampleSizePerType,
        int totalItems,
        long reviewedItems,
        boolean representativeOfPrevalence,
        List<RouteReviewSampleItemDto> items
) {
}
