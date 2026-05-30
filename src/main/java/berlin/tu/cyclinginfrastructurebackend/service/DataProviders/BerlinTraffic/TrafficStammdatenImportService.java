package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.BerlinTraffic;

import berlin.tu.cyclinginfrastructurebackend.domain.TrafficDetector;
import berlin.tu.cyclinginfrastructurebackend.repository.TrafficDetectorRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class TrafficStammdatenImportService {

    private static final Logger log = LoggerFactory.getLogger(TrafficStammdatenImportService.class);
    private static final String DEFAULT_STAMMDATEN_URL =
            "https://mdhopendata.blob.core.windows.net/verkehrsdetektion/Stammdaten_Verkehrsdetektion_2022_07_20.xlsx";

    private final BerlinTrafficArchiveService archiveService;
    private final TrafficDetectorRepository detectorRepository;
    private final String stammdatenUrl;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    public TrafficStammdatenImportService(BerlinTrafficArchiveService archiveService,
                                          TrafficDetectorRepository detectorRepository,
                                          @Value("${enrichment.traffic.stammdaten-url:" + DEFAULT_STAMMDATEN_URL + "}") String stammdatenUrl) {
        this.archiveService = archiveService;
        this.detectorRepository = detectorRepository;
        this.stammdatenUrl = stammdatenUrl;
    }

    public synchronized boolean ensureImported() {
        if (detectorRepository.count() > 0) {
            return true;
        }
        return archiveService.ensureCachedFile(stammdatenUrl, "Stammdaten_Verkehrsdetektion_2022_07_20.xlsx")
                .map(this::importWorkbook)
                .orElse(false);
    }

    private boolean importWorkbook(Path workbookPath) {
        int imported = 0;
        int skipped = 0;

        try (InputStream input = java.nio.file.Files.newInputStream(workbookPath);
             Workbook workbook = WorkbookFactory.create(input)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                log.warn("Traffic Stammdaten workbook '{}' has no sheets.", workbookPath);
                return false;
            }

            Map<String, Integer> header = readHeader(sheet.getRow(0));
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }

                String detNameAlt = value(row, header, "DET_NAME_ALT");
                if (detNameAlt == null || detNameAlt.isBlank()) {
                    skipped++;
                    continue;
                }

                TrafficDetector detector = detectorRepository.findByDetNameAlt(detNameAlt)
                        .orElseGet(TrafficDetector::new);
                detector.setDetNameAlt(detNameAlt);
                detector.setMqKurzname(value(row, header, "MQ_KURZNAME"));
                detector.setDetNameNeu(value(row, header, "DET_NAME_NEU"));
                detector.setDetId15(value(row, header, "DET_ID15"));
                detector.setMqId15(value(row, header, "MQ_ID15"));
                detector.setStreet(value(row, header, "STRASSE"));
                detector.setPosition(value(row, header, "POSITION"));
                detector.setPositionDetail(value(row, header, "POS_DETAIL"));
                detector.setDirection(value(row, header, "RICHTUNG"));
                detector.setLane(value(row, header, "SPUR"));
                detector.setActiveFrom(localDate(row, header, "INBETRIEBNAHME"));
                detector.setActiveTo(localDate(row, header, "ABBAUDATUM"));
                detector.setDeinstalled(!isBlank(value(row, header, "DEINSTALLIERT")));

                Double lon = numeric(row, header, "LÄNGE (WGS84)");
                Double lat = numeric(row, header, "BREITE (WGS84)");
                if (lon != null && lat != null) {
                    detector.setLocation(geometryFactory.createPoint(new Coordinate(lon, lat)));
                }

                detectorRepository.save(detector);
                imported++;
            }
        } catch (Exception e) {
            log.error("Failed to import traffic Stammdaten '{}': {}", workbookPath, e.getMessage());
            return false;
        }

        log.info("Traffic Stammdaten import complete: {} imported/updated, {} skipped.", imported, skipped);
        return imported > 0;
    }

    private Map<String, Integer> readHeader(Row row) {
        Map<String, Integer> header = new HashMap<>();
        if (row == null) {
            return header;
        }
        for (Cell cell : row) {
            header.put(normalize(cell.getStringCellValue()), cell.getColumnIndex());
        }
        return header;
    }

    private String value(Row row, Map<String, Integer> header, String column) {
        Integer index = header.get(normalize(column));
        if (index == null) {
            return null;
        }

        Cell cell = row.getCell(index);
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }

        if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate().toString();
            }
            return BigDecimal.valueOf(cell.getNumericCellValue()).toBigInteger().toString();
        }

        if (cell.getCellType() == CellType.BOOLEAN) {
            return Boolean.toString(cell.getBooleanCellValue());
        }

        String text = cell.getStringCellValue();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private Double numeric(Row row, Map<String, Integer> header, String column) {
        Integer index = header.get(normalize(column));
        if (index == null) {
            return null;
        }
        Cell cell = row.getCell(index);
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        try {
            return Double.parseDouble(cell.getStringCellValue().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate localDate(Row row, Map<String, Integer> header, String column) {
        Integer index = header.get(normalize(column));
        if (index == null) {
            return null;
        }
        Cell cell = row.getCell(index);
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
            String text = value(row, header, column);
            return isBlank(text) ? null : LocalDate.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toUpperCase(Locale.GERMAN);
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
