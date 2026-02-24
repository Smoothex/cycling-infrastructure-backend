package berlin.tu.cyclinginfrastructurebackend.service.dto;

import com.opencsv.bean.CsvBindByName;
import lombok.Data;

@Data
public class RidePointCsvBean {

    @CsvBindByName(column = "lat")
    private Double lat;

    @CsvBindByName(column = "lon")
    private Double lon;

    @CsvBindByName(column = "X")
    private Double x;

    @CsvBindByName(column = "Y")
    private Double y;

    @CsvBindByName(column = "Z")
    private Double z;

    @CsvBindByName(column = "timeStamp")
    private Long timeStamp;

    @CsvBindByName(column = "acc")
    private Double acc;

    @CsvBindByName(column = "a")
    private Double a;

    @CsvBindByName(column = "b")
    private Double b;

    @CsvBindByName(column = "c")
    private Double c;

    @CsvBindByName(column = "obsClosePassEvent")
    private Boolean obsClosePassEvent;

    @CsvBindByName(column = "obsDistanceLeft1")
    private Double obsDistanceLeft1;

    @CsvBindByName(column = "obsDistanceLeft2")
    private Double obsDistanceLeft2;

    @CsvBindByName(column = "obsDistanceRight1")
    private Double obsDistanceRight1;

    @CsvBindByName(column = "obsDistanceRight2")
    private Double obsDistanceRight2;

    @CsvBindByName(column = "RC")
    private Double rc;

    @CsvBindByName(column = "RX")
    private Double rx;

    @CsvBindByName(column = "RY")
    private Double ry;

    @CsvBindByName(column = "RZ")
    private Double rz;

    @CsvBindByName(column = "XL")
    private Double xl;

    @CsvBindByName(column = "YL")
    private Double yl;

    @CsvBindByName(column = "ZL")
    private Double zl;
}

