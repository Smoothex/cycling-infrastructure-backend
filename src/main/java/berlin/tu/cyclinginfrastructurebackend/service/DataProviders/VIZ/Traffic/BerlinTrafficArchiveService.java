package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.Traffic;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficSourceType;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BerlinTrafficArchiveService {

    private static final Logger log = LoggerFactory.getLogger(BerlinTrafficArchiveService.class);
    private static final String BASE_URL = "https://mdhopendata.blob.core.windows.net/verkehrsdetektion";

    private final RestClient restClient;
    private final Path cacheDir;
    private final Map<Path, Map<String, TrafficMeasurement>> parsedFileCache = new ConcurrentHashMap<>();
    private final Set<Path> failedExtractionArchives = ConcurrentHashMap.newKeySet();

    public BerlinTrafficArchiveService(RestClient.Builder restClientBuilder,
                                       @Value("${enrichment.traffic.cache-dir:./data/berlinTraffic/cache}") String cacheDir) {
        this.restClient = restClientBuilder.build();
        this.cacheDir = Path.of(cacheDir);
    }

    TrafficLookupResult findNewDetectorMeasurement(YearMonth month, String detectorName, LocalDate date, int hour) {
        if (detectorName == null || detectorName.isBlank()) {
            return TrafficLookupResult.sourceMissing();
        }

        Optional<Path> extractedDir = ensureNewArchiveExtracted(month);
        if (extractedDir.isEmpty()) {
            return TrafficLookupResult.sourceMissing();
        }

        Optional<Path> detectorFile = findCsvGzByStem(extractedDir.get(), detectorName);
        if (detectorFile.isEmpty()) {
            return TrafficLookupResult.noMeasurement();
        }

        try {
            Map<String, TrafficMeasurement> rows = parsedFileCache.computeIfAbsent(
                    detectorFile.get(),
                    path -> readNewDetectorRows(path, TrafficSourceType.NEW_DETECTOR)
            );
            TrafficMeasurement measurement = rows.get(hourKey(date, hour));
            return measurement == null ? TrafficLookupResult.noMeasurement() : TrafficLookupResult.found(measurement);
        } catch (RuntimeException e) {
            log.warn("Failed to read new detector traffic file '{}': {}", detectorFile.get(), e.getMessage());
            return TrafficLookupResult.noMeasurement();
        }
    }

    TrafficLookupResult findOldDetectorMeasurement(YearMonth month, String detId15, LocalDate date, int hour) {
        if (detId15 == null || detId15.isBlank()) {
            return TrafficLookupResult.sourceMissing();
        }

        String relativePath = "%d/alte_qualitaetssicherung/Fahrstreifendetektoren/det_val_hr_%s.csv.gz"
                .formatted(month.getYear(), monthToken(month));
        Optional<Path> source = downloadIfAvailable(relativePath);
        if (source.isEmpty()) {
            return TrafficLookupResult.sourceMissing();
        }

        Map<String, TrafficMeasurement> rows = parsedFileCache.computeIfAbsent(
                source.get(),
                path -> readOldDetectorRows(path, TrafficSourceType.OLD_DETECTOR)
        );
        TrafficMeasurement measurement = rows.get(detId15 + "|" + hourKey(date, hour));
        return measurement == null ? TrafficLookupResult.noMeasurement() : TrafficLookupResult.found(measurement);
    }

    TrafficLookupResult findOldMqMeasurement(YearMonth month, String mqKurzname, LocalDate date, int hour) {
        if (mqKurzname == null || mqKurzname.isBlank()) {
            return TrafficLookupResult.sourceMissing();
        }

        String relativePath = "%d/alte_qualitaetssicherung/Messquerschnitte/mq_hr_%s.csv.gz"
                .formatted(month.getYear(), monthToken(month));
        Optional<Path> source = downloadIfAvailable(relativePath);
        if (source.isEmpty()) {
            return TrafficLookupResult.sourceMissing();
        }

        Map<String, TrafficMeasurement> rows = parsedFileCache.computeIfAbsent(
                source.get(),
                path -> readOldMqRows(path, TrafficSourceType.OLD_MQ)
        );
        TrafficMeasurement measurement = rows.get(mqKurzname + "|" + hourKey(date, hour));
        return measurement == null ? TrafficLookupResult.noMeasurement() : TrafficLookupResult.found(measurement);
    }

    Optional<Path> ensureCachedFile(String absoluteUrl, String localFileName) {
        return downloadIfAvailable(absoluteUrl, cacheDir.resolve(localFileName));
    }

    private Optional<Path> ensureNewArchiveExtracted(YearMonth month) {
        String singular = "%d/neue_qualitaetssicherung/Fahrstreifendetektoren/detektor_%s.tgz"
                .formatted(month.getYear(), monthToken(month));
        String plural = "%d/neue_qualitaetssicherung/Fahrstreifendetektoren/detektoren_%s.tgz"
                .formatted(month.getYear(), monthToken(month));

        Optional<Path> archive = downloadIfAvailable(singular);
        if (archive.isEmpty()) {
            archive = downloadIfAvailable(plural);
        }
        if (archive.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(extractMonthArchive(archive.get(), month));
        } catch (IllegalStateException e) {
            if (failedExtractionArchives.add(archive.get().toAbsolutePath().normalize())) {
                log.warn("New traffic archive for {} is unavailable after download; falling back to old sources if present. Cause: {}",
                        monthToken(month), e.getMessage());
            }
            return Optional.empty();
        }
    }

    private Optional<Path> downloadIfAvailable(String relativePath) {
        return downloadIfAvailable(BASE_URL + "/" + relativePath, cacheDir.resolve(relativePath));
    }

    private Optional<Path> downloadIfAvailable(String url, Path target) {
        try {
            Files.createDirectories(target.getParent());
            URI uri = URI.create(url);
            RemoteMetadata remote = readRemoteMetadata(uri);
            Path metaPath = metadataPath(target);

            if (Files.exists(target) && isCachedCopyCurrent(metaPath, remote)) {
                return Optional.of(target);
            }

            byte[] body = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(byte[].class);
            if (body == null) {
                return Files.exists(target) ? Optional.of(target) : Optional.empty();
            }

            Files.write(target, body);
            writeMetadata(metaPath, remote);
            return Optional.of(target);
        } catch (HttpClientErrorException.NotFound e) {
            return Files.exists(target) ? Optional.of(target) : Optional.empty();
        } catch (Exception e) {
            if (Files.exists(target)) {
                log.warn("Using cached traffic source '{}' because refresh failed: {}", target, e.getMessage());
                return Optional.of(target);
            }
            log.warn("Traffic source unavailable '{}': {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    private RemoteMetadata readRemoteMetadata(URI uri) {
        ResponseEntity<Void> response = restClient.head()
                .uri(uri)
                .retrieve()
                .toBodilessEntity();
        return new RemoteMetadata(
                response.getHeaders().getLastModified(),
                response.getHeaders().getContentLength()
        );
    }

    private boolean isCachedCopyCurrent(Path metaPath, RemoteMetadata remote) throws IOException {
        if (!Files.exists(metaPath)) {
            return false;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(metaPath)) {
            properties.load(input);
        }

        long cachedLastModified = Long.parseLong(properties.getProperty("lastModified", "-1"));
        long cachedContentLength = Long.parseLong(properties.getProperty("contentLength", "-1"));

        boolean lastModifiedMatches = remote.lastModified() <= 0 || cachedLastModified == remote.lastModified();
        boolean lengthMatches = remote.contentLength() < 0 || cachedContentLength == remote.contentLength();
        return lastModifiedMatches && lengthMatches;
    }

    private void writeMetadata(Path metaPath, RemoteMetadata remote) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("lastModified", Long.toString(remote.lastModified()));
        properties.setProperty("contentLength", Long.toString(remote.contentLength()));
        try (var output = Files.newOutputStream(metaPath)) {
            properties.store(output, "Berlin traffic source metadata");
        }
    }

    private Path metadataPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".metadata");
    }

    private synchronized Path extractMonthArchive(Path archive, YearMonth month) {
        Path normalizedArchive = archive.toAbsolutePath().normalize();
        if (failedExtractionArchives.contains(normalizedArchive)) {
            throw new IllegalStateException("Previous extraction attempt failed for " + archive);
        }

        Path outputDir = cacheDir.resolve("extracted").resolve(monthToken(month)).toAbsolutePath().normalize();
        Path marker = outputDir.resolve(".extract-complete");
        try {
            if (Files.exists(marker) && Files.getLastModifiedTime(marker).compareTo(Files.getLastModifiedTime(normalizedArchive)) >= 0) {
                return outputDir;
            }

            deleteDirectory(outputDir);
            Files.createDirectories(outputDir);

            try (InputStream fileInput = Files.newInputStream(normalizedArchive);
                 BufferedInputStream bufferedInput = new BufferedInputStream(fileInput);
                 GzipCompressorInputStream gzipInput = new GzipCompressorInputStream(bufferedInput);
                 TarArchiveInputStream tarInput = new TarArchiveInputStream(gzipInput)) {

                TarArchiveEntry entry;
                while ((entry = tarInput.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }

                    Path target = outputDir.resolve(entry.getName()).normalize();
                    if (!target.startsWith(outputDir)) {
                        throw new IOException("Archive entry escapes extraction directory: " + entry.getName());
                    }

                    Files.createDirectories(target.getParent());
                    try (OutputStream output = Files.newOutputStream(target)) {
                        tarInput.transferTo(output);
                    }
                }
            }

            Files.writeString(marker, "ok", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to extract traffic archive " + archive + ": " + e.getMessage(), e);
        }
        return outputDir;
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.delete(path);
            }
        }
    }

    private Optional<Path> findCsvGzByStem(Path root, String stem) {
        try (var paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> isCsvFileForStem(path, stem))
                    .findFirst();
        } catch (IOException e) {
            log.warn("Failed to scan extracted traffic archive '{}': {}", root, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean isCsvFileForStem(Path path, String stem) {
        String fileName = path.getFileName().toString();
        return fileName.equalsIgnoreCase(stem + ".csv.gz")
                || fileName.equalsIgnoreCase(stem + ".csv");
    }

    private Map<String, TrafficMeasurement> readNewDetectorRows(Path path, TrafficSourceType sourceType) {
        Map<String, TrafficMeasurement> rows = new HashMap<>();
        readCsv(path, (header, values) -> {
            LocalDate date = parseDate(value(header, values, "datum", "datum (ortszeit)"));
            Integer hour = parseInteger(value(header, values, "stunde", "stunde des tages (ortszeit)"));
            if (date == null || hour == null) {
                return;
            }

            TrafficMeasurement measurement = new TrafficMeasurement(
                    sourceType,
                    parseInteger(value(header, values, "kfz", "qkfz")),
                    parseDouble(value(header, values, "vkfz")),
                    parseInteger(value(header, values, "qpkw")),
                    parseDouble(value(header, values, "vpkw")),
                    parseInteger(value(header, values, "qlkw")),
                    parseDouble(value(header, values, "vlkw")),
                    null,
                    parseDouble(value(header, values, "vollstaendigkeit", "datapoints_rel"))
            );
            rows.put(hourKey(date, hour), measurement);
        });
        return rows;
    }

    private Map<String, TrafficMeasurement> readOldDetectorRows(Path path, TrafficSourceType sourceType) {
        Map<String, TrafficMeasurement> rows = new HashMap<>();
        readCsvGz(path, (header, values) -> {
            String detId = value(header, values, "detid_15");
            LocalDate date = parseDate(value(header, values, "tag"));
            Integer hour = parseInteger(value(header, values, "stunde"));
            if (isBlank(detId) || date == null || hour == null) {
                return;
            }

            TrafficMeasurement measurement = new TrafficMeasurement(
                    sourceType,
                    parseInteger(value(header, values, "q_kfz_det_hr")),
                    nullIfMinusOne(value(header, values, "v_kfz_det_hr")),
                    parseInteger(value(header, values, "q_pkw_det_hr")),
                    nullIfMinusOne(value(header, values, "v_pkw_det_hr")),
                    parseInteger(value(header, values, "q_lkw_det_hr")),
                    nullIfMinusOne(value(header, values, "v_lkw_det_hr")),
                    parseDouble(value(header, values, "qualitaet")),
                    null
            );
            rows.put(detId + "|" + hourKey(date, hour), measurement);
        });
        return rows;
    }

    private Map<String, TrafficMeasurement> readOldMqRows(Path path, TrafficSourceType sourceType) {
        Map<String, TrafficMeasurement> rows = new HashMap<>();
        readCsvGz(path, (header, values) -> {
            String mqName = value(header, values, "mq_name");
            LocalDate date = parseDate(value(header, values, "tag"));
            Integer hour = parseInteger(value(header, values, "stunde"));
            if (isBlank(mqName) || date == null || hour == null) {
                return;
            }

            TrafficMeasurement measurement = new TrafficMeasurement(
                    sourceType,
                    parseInteger(value(header, values, "q_kfz_mq_hr")),
                    nullIfMinusOne(value(header, values, "v_kfz_mq_hr")),
                    parseInteger(value(header, values, "q_pkw_mq_hr")),
                    nullIfMinusOne(value(header, values, "v_pkw_mq_hr")),
                    parseInteger(value(header, values, "q_lkw_mq_hr")),
                    nullIfMinusOne(value(header, values, "v_lkw_mq_hr")),
                    parseDouble(value(header, values, "qualitaet")),
                    null
            );
            rows.put(mqName + "|" + hourKey(date, hour), measurement);
        });
        return rows;
    }

    private void readCsvGz(Path path, CsvRowConsumer consumer) {
        readCompressedCsv(path, consumer);
    }

    private void readCsv(Path path, CsvRowConsumer consumer) {
        if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")) {
            readCompressedCsv(path, consumer);
            return;
        }
        try (InputStream input = Files.newInputStream(path);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
             CSVReader csvReader = new CSVReaderBuilder(reader)
                     .withCSVParser(new CSVParserBuilder().withSeparator(';').build())
                     .build()) {
            readRows(csvReader, consumer);
        } catch (IOException | CsvValidationException e) {
            throw new IllegalStateException("Failed to parse CSV " + path, e);
        }
    }

    private void readCompressedCsv(Path path, CsvRowConsumer consumer) {
        try (InputStream input = Files.newInputStream(path);
             GzipCompressorInputStream gzipInput = new GzipCompressorInputStream(input);
             InputStreamReader reader = new InputStreamReader(gzipInput, StandardCharsets.UTF_8);
             CSVReader csvReader = new CSVReaderBuilder(reader)
                     .withCSVParser(new CSVParserBuilder().withSeparator(';').build())
                     .build()) {

            readRows(csvReader, consumer);
        } catch (IOException | CsvValidationException e) {
            throw new IllegalStateException("Failed to parse CSV " + path, e);
        }
    }

    private void readRows(CSVReader csvReader, CsvRowConsumer consumer) throws IOException, CsvValidationException {
        String[] headerRow = csvReader.readNext();
        if (headerRow == null) {
            return;
        }

        Map<String, Integer> header = new HashMap<>();
        for (int i = 0; i < headerRow.length; i++) {
            header.put(normalize(headerRow[i]), i);
        }

        String[] values;
        while ((values = csvReader.readNext()) != null) {
            consumer.accept(header, values);
        }
    }

    private String value(Map<String, Integer> header, String[] values, String... aliases) {
        for (String alias : aliases) {
            Integer index = header.get(normalize(alias));
            if (index != null && index < values.length) {
                return values[index];
            }
        }
        return null;
    }

    private String hourKey(LocalDate date, int hour) {
        return date + "|" + hour;
    }

    private String monthToken(YearMonth month) {
        return "%04d_%02d".formatted(month.getYear(), month.getMonthValue());
    }

    private LocalDate parseDate(String text) {
        if (isBlank(text)) {
            return null;
        }
        try {
            if (text.contains(".")) {
                String[] parts = text.split("\\.");
                return LocalDate.of(
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[0])
                );
            }
            return LocalDate.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseInteger(String text) {
        Double value = parseDouble(text);
        return value == null ? null : value.intValue();
    }

    private Double parseDouble(String text) {
        if (isBlank(text)) {
            return null;
        }
        String normalized = text.trim();
        if ("nan".equalsIgnoreCase(normalized)) {
            return null;
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double nullIfMinusOne(String text) {
        Double value = parseDouble(text);
        return value != null && value == -1.0 ? null : value;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.GERMAN);
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private record RemoteMetadata(long lastModified, long contentLength) {
    }

    @FunctionalInterface
    private interface CsvRowConsumer {
        void accept(Map<String, Integer> header, String[] values);
    }
}
