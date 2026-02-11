package berlin.tu.cyclinginfrastructurebackend.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum IncidentType {
    NOTHING(0),
    CLOSE_PASS(1),
    PULLING_IN_OUT(2),
    NEAR_HOOK(3),
    HEAD_ON(4),
    TAILGATING(5),
    NEAR_DOORING(6),
    DODGING(7),
    OTHER(8),
    DUMMY(-5);

    private final int code;

    public static IncidentType fromCode(int code) {
        return Arrays.stream(values())
                .filter(t -> t.code == code)
                .findFirst()
                .orElse(NOTHING);
    }
}

