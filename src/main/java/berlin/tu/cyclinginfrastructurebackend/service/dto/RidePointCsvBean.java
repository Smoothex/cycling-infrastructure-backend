package berlin.tu.cyclinginfrastructurebackend.service.dto;

import com.opencsv.bean.CsvBindByName;
import lombok.Data;

@Data
public class RidePointCsvBean {

    @CsvBindByName(column = "lat")
    private Double lat;

    @CsvBindByName(column = "lon")
    private Double lon;

    @CsvBindByName(column = "timeStamp")
    private Long timeStamp;

    @CsvBindByName(column = "acc")
    private Double acc;

}
