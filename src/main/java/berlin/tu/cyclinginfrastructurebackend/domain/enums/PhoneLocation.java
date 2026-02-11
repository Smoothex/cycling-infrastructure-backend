package berlin.tu.cyclinginfrastructurebackend.domain.enums;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import java.util.Arrays;
@Getter
@RequiredArgsConstructor
public enum PhoneLocation {
    POCKET(0),
    HANDLEBAR(1),
    JACKET_POCKET(2),
    HAND(3),
    BASKET(4),
    BAG(5),
    OTHER(6);
    private final int code;
    public static PhoneLocation fromCode(int code) {
        return Arrays.stream(values())
                .filter(t -> t.code == code)
                .findFirst()
                .orElse(OTHER);
    }
}
