package berlin.tu.cyclinginfrastructurebackend.service.dto;

import com.opencsv.bean.CsvBindByName;
import lombok.Data;

@Data
public class RidePointCsvBean {

    @CsvBindByName(column = "lat", required = false)
    private Double lat;

    @CsvBindByName(column = "lon", required = false)
    private Double lon;

    @CsvBindByName(column = "X", required = false)
    private Double x;

    @CsvBindByName(column = "Y", required = false)
    private Double y;

    @CsvBindByName(column = "Z", required = false)
    private Double z;

    @CsvBindByName(column = "timeStamp", required = false)
    private Long timeStamp;

    @CsvBindByName(column = "acc", required = false)
    private Double acc;

    @CsvBindByName(column = "a", required = false)
    private Double a;

    @CsvBindByName(column = "b", required = false)
    private Double b;

    @CsvBindByName(column = "c", required = false)
    private Double c;

    @CsvBindByName(column = "obsClosePassEvent", required = false)
    private Boolean obsClosePassEvent;

    @CsvBindByName(column = "obsDistanceLeft1", required = false)
    private Double obsDistanceLeft1;

    @CsvBindByName(column = "obsDistanceLeft2", required = false)
    private Double obsDistanceLeft2;

    @CsvBindByName(column = "obsDistanceRight1", required = false)
    private Double obsDistanceRight1;

    @CsvBindByName(column = "obsDistanceRight2", required = false)
    private Double obsDistanceRight2;

    @CsvBindByName(column = "RC", required = false)
    private Double rc;

    @CsvBindByName(column = "RX", required = false)
    private Double rx;

    @CsvBindByName(column = "RY", required = false)
    private Double ry;

    @CsvBindByName(column = "RZ", required = false)
    private Double rz;

    @CsvBindByName(column = "XL", required = false)
    private Double xl;

    @CsvBindByName(column = "YL", required = false)
    private Double yl;

    @CsvBindByName(column = "ZL", required = false)
    private Double zl;
}

