package berlin.tu.cyclinginfrastructurebackend.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum BikeType {
    NOT_CHOSEN(0),
    CITY_TREKKING_BIKE(1),
    ROAD_RACING_BIKE(2),
    E_BIKE(3),
    RECUMBENT_BICYCLE(4),
    FREIGHT_BICYCLE(5),
    TANDEM_BICYCLE(6),
    MOUNTAIN_BIKE(7),
    OTHER(8);

    private final int code;

    public static BikeType fromCode(int code) {
        return Arrays.stream(values())
                .filter(t -> t.code == code)
                .findFirst()
                .orElse(NOT_CHOSEN);
    }
}

