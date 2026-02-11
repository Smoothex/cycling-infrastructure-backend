package berlin.tu.cyclinginfrastructurebackend.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum ParticipantType {
    BUS(1),
    CYCLIST(2),
    PEDESTRIAN(3),
    DELIVERY_VAN(4),
    TRUCK(5),
    MOTORCYCLE(6),
    CAR(7),
    TAXI(8),
    OTHER(9),
    SCOOTER(10);

    private final int code;

    public static ParticipantType fromCode(int code) {
        return Arrays.stream(values())
                .filter(t -> t.code == code)
                .findFirst()
                .orElse(OTHER);
    }
}

