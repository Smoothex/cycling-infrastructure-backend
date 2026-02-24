package berlin.tu.cyclinginfrastructurebackend.service.dto;

import com.opencsv.bean.CsvBindByName;
import lombok.Data;

@Data
public class IncidentCsvBean {
    @CsvBindByName(column = "key")
    private Integer key;

    @CsvBindByName(column = "lat")
    private Double lat;

    @CsvBindByName(column = "lon")
    private Double lon;

    @CsvBindByName(column = "ts")
    private Long ts;

    @CsvBindByName(column = "bike")
    private Integer bike;

    @CsvBindByName(column = "childCheckBox")
    private Boolean childCheckBox;

    @CsvBindByName(column = "trailerCheckBox")
    private Boolean trailerCheckBox;

    @CsvBindByName(column = "pLoc")
    private Integer pLoc;

    @CsvBindByName(column = "incident")
    private Integer incident;

    @CsvBindByName(column = "i1")
    private Boolean i1;

    @CsvBindByName(column = "i2")
    private Boolean i2;

    @CsvBindByName(column = "i3")
    private Boolean i3;

    @CsvBindByName(column = "i4")
    private Boolean i4;

    @CsvBindByName(column = "i5")
    private Boolean i5;

    @CsvBindByName(column = "i6")
    private Boolean i6;

    @CsvBindByName(column = "i7")
    private Boolean i7;

    @CsvBindByName(column = "i8")
    private Boolean i8;

    @CsvBindByName(column = "i9")
    private Boolean i9;

    @CsvBindByName(column = "scary")
    private Boolean scary;

    @CsvBindByName(column = "desc")
    private String desc;

    @CsvBindByName(column = "i10")
    private Boolean i10;
}



