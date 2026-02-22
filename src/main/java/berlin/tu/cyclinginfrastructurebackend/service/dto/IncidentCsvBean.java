package berlin.tu.cyclinginfrastructurebackend.service.dto;

import com.opencsv.bean.CsvBindByName;
import lombok.Data;

@Data
public class IncidentCsvBean {
    @CsvBindByName(column = "key", required = false)
    private Integer key;

    @CsvBindByName(column = "lat", required = false)
    private Double lat;

    @CsvBindByName(column = "lon", required = false)
    private Double lon;

    @CsvBindByName(column = "ts", required = false)
    private Long ts;

    @CsvBindByName(column = "bike", required = false)
    private Integer bike;

    @CsvBindByName(column = "childCheckBox", required = false)
    private Boolean childCheckBox;

    @CsvBindByName(column = "trailerCheckBox", required = false)
    private Boolean trailerCheckBox;

    @CsvBindByName(column = "pLoc", required = false)
    private Integer pLoc;

    @CsvBindByName(column = "incident", required = false)
    private Integer incident;

    @CsvBindByName(column = "i1", required = false)
    private Boolean i1;

    @CsvBindByName(column = "i2", required = false)
    private Boolean i2;

    @CsvBindByName(column = "i3", required = false)
    private Boolean i3;

    @CsvBindByName(column = "i4", required = false)
    private Boolean i4;

    @CsvBindByName(column = "i5", required = false)
    private Boolean i5;

    @CsvBindByName(column = "i6", required = false)
    private Boolean i6;

    @CsvBindByName(column = "i7", required = false)
    private Boolean i7;

    @CsvBindByName(column = "i8", required = false)
    private Boolean i8;

    @CsvBindByName(column = "i9", required = false)
    private Boolean i9;

    @CsvBindByName(column = "scary", required = false)
    private Boolean scary;

    @CsvBindByName(column = "desc", required = false)
    private String desc;

    @CsvBindByName(column = "i10", required = false)
    private Boolean i10;
}



