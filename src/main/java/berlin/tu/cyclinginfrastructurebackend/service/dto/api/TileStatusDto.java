package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

public record TileStatusDto(
        String state,
        Long generatedAt,
        String lastError
) {
}
