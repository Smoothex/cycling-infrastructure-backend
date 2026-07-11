package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.Traffic;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.TrafficDetector;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficCondition;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficEnrichmentStatus;
import berlin.tu.cyclinginfrastructurebackend.repository.TrafficDetectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class TrafficDataProvider {

    private static final Logger log = LoggerFactory.getLogger(TrafficDataProvider.class);
    private static final ZoneId BERLIN_ZONE = ZoneId.of("Europe/Berlin");

    private final TrafficStammdatenImportService stammdatenImportService;
    private final TrafficDetectorRepository detectorRepository;
    private final BerlinTrafficArchiveService archiveService;

    @Value("${enrichment.traffic.match-radius-meters:75}")
    private double matchRadiusMeters;

    @Value("${enrichment.traffic.candidate-limit:25}")
    private int candidateLimit;

    @Value("${enrichment.traffic.min-quality:0.75}")
    private double minQuality;

    @Value("${enrichment.traffic.min-completeness-percent:75}")
    private double minCompletenessPercent;

    @Value("${enrichment.traffic.light-volume-threshold:150}")
    private int lightVolumeThreshold;

    @Value("${enrichment.traffic.heavy-volume-threshold:800}")
    private int heavyVolumeThreshold;

    @Value("${enrichment.traffic.low-speed-kmh:30}")
    private double lowSpeedKmh;

    @Value("${enrichment.traffic.congested-speed-kmh:20}")
    private double congestedSpeedKmh;

    public TrafficDataProvider(TrafficStammdatenImportService stammdatenImportService,
                               TrafficDetectorRepository detectorRepository,
                               BerlinTrafficArchiveService archiveService) {
        this.stammdatenImportService = stammdatenImportService;
        this.detectorRepository = detectorRepository;
        this.archiveService = archiveService;
    }

    public void enrichEvent(SegmentEvent event) {
        try {
            enrichEventInternal(event);
        } catch (Exception e) {
            log.error("Traffic enrichment failed for event {}: {}", event != null ? event.getId() : null, e.getMessage());
            markMissing(event, TrafficEnrichmentStatus.ERROR);
        }
    }

    private void enrichEventInternal(SegmentEvent event) {
        if (event == null || event.getSegment() == null || event.getEventTimestamp() == null) {
            markMissing(event, TrafficEnrichmentStatus.ERROR);
            return;
        }

        if (!stammdatenImportService.ensureImported()) {
            log.warn("Traffic Stammdaten are unavailable; cannot enrich event {}.", event.getId());
            markMissing(event, TrafficEnrichmentStatus.ERROR);
            return;
        }

        ZonedDateTime eventTime = Instant.ofEpochMilli(event.getEventTimestamp()).atZone(BERLIN_ZONE);
        LocalDate date = eventTime.toLocalDate();
        YearMonth month = YearMonth.from(eventTime);
        int hour = eventTime.getHour();

        Optional<TrafficDetector> detector = findBestDetector(event, date);
        if (detector.isEmpty()) {
            markMissing(event, TrafficEnrichmentStatus.NO_DETECTOR_MATCH);
            return;
        }

        LookupOutcome outcome = findBestMeasurement(detector.get(), month, date, hour);
        if (outcome.measurement().isPresent()) {
            applyTraffic(event, outcome.measurement().get());
            return;
        }

        markMissing(event, outcome.status());
    }

    private Optional<TrafficDetector> findBestDetector(SegmentEvent event, LocalDate eventDate) {
        List<TrafficDetector> candidates = detectorRepository.findNearestToSegment(
                event.getSegment().getId(),
                matchRadiusMeters,
                candidateLimit
        );

        return candidates.stream()
                .filter(candidate -> isActive(candidate, eventDate))
                .max(Comparator.comparingDouble(candidate -> scoreCandidate(event, candidate, candidates.indexOf(candidate))));
    }

    private boolean isActive(TrafficDetector detector, LocalDate eventDate) {
        if (Boolean.TRUE.equals(detector.getDeinstalled())) {
            return false;
        }
        if (detector.getActiveFrom() != null && eventDate.isBefore(detector.getActiveFrom())) {
            return false;
        }
        return detector.getActiveTo() == null || !eventDate.isAfter(detector.getActiveTo());
    }

    private double scoreCandidate(SegmentEvent event, TrafficDetector detector, int distanceRank) {
        double score = Math.max(0, 100 - distanceRank * 3);

        if (streetMatches(event.getSegment().getStreetName(), detector.getStreet())) {
            score += 25;
        }

        Double directionBearing = directionBearing(detector.getDirection());
        if (directionBearing != null && event.getPathBearingDegrees() != null) {
            double delta = bearingDelta(event.getPathBearingDegrees(), directionBearing);
            if (delta <= 45) {
                score += 20;
            } else if (delta <= 90) {
                score += 8;
            } else if (delta >= 135) {
                score -= 20;
            }
        }

        return score;
    }

    private LookupOutcome findBestMeasurement(TrafficDetector detector, YearMonth month, LocalDate date, int hour) {
        boolean anySourceFileAvailable = false;
        boolean anyMeasurementFound = false;
        boolean anyLowQuality = false;

        TrafficLookupResult newDetector = archiveService.findNewDetectorMeasurement(
                month,
                detector.getDetNameAlt(),
                date,
                hour
        );
        anySourceFileAvailable |= newDetector.sourceFileAvailable();
        if (newDetector.measurement().isPresent()) {
            anyMeasurementFound = true;
            TrafficMeasurement measurement = newDetector.measurement().get();
            if (isQualityAcceptable(measurement)) {
                return LookupOutcome.enriched(measurement);
            }
            anyLowQuality = true;
        }

        TrafficLookupResult oldDetector = archiveService.findOldDetectorMeasurement(
                month,
                detector.getDetId15(),
                date,
                hour
        );
        anySourceFileAvailable |= oldDetector.sourceFileAvailable();
        if (oldDetector.measurement().isPresent()) {
            anyMeasurementFound = true;
            TrafficMeasurement measurement = oldDetector.measurement().get();
            if (isQualityAcceptable(measurement)) {
                return LookupOutcome.enriched(measurement);
            }
            anyLowQuality = true;
        }

        TrafficLookupResult oldMq = archiveService.findOldMqMeasurement(
                month,
                detector.getMqKurzname(),
                date,
                hour
        );
        anySourceFileAvailable |= oldMq.sourceFileAvailable();
        if (oldMq.measurement().isPresent()) {
            anyMeasurementFound = true;
            TrafficMeasurement measurement = oldMq.measurement().get();
            if (isQualityAcceptable(measurement)) {
                return LookupOutcome.enriched(measurement);
            }
            anyLowQuality = true;
        }

        if (!anySourceFileAvailable) {
            return LookupOutcome.missing(TrafficEnrichmentStatus.NO_SOURCE_FILE);
        }
        if (anyMeasurementFound && anyLowQuality) {
            return LookupOutcome.missing(TrafficEnrichmentStatus.LOW_QUALITY);
        }
        return LookupOutcome.missing(TrafficEnrichmentStatus.NO_MEASUREMENT);
    }

    private boolean isQualityAcceptable(TrafficMeasurement measurement) {
        if (measurement.volumeKfz() != null && measurement.volumeKfz() < 0) {
            return false;
        }
        if (measurement.speedKfz() != null && (measurement.speedKfz() < 0 || measurement.speedKfz() > 160)) {
            return false;
        }
        return switch (measurement.sourceType()) {
            case NEW_DETECTOR -> measurement.completenessPercent() != null
                    && measurement.completenessPercent() >= minCompletenessPercent;
            case OLD_DETECTOR, OLD_MQ -> measurement.quality() != null
                    && measurement.quality() >= minQuality;
        };
    }

    private void applyTraffic(SegmentEvent event, TrafficMeasurement measurement) {
        event.setTrafficVolumeKfz(measurement.volumeKfz());
        event.setTrafficSpeedKfz(measurement.speedKfz());
        event.setTrafficVolumePkw(measurement.volumePkw());
        event.setTrafficSpeedPkw(measurement.speedPkw());
        event.setTrafficVolumeLkw(measurement.volumeLkw());
        event.setTrafficSpeedLkw(measurement.speedLkw());
        event.setTrafficSourceType(measurement.sourceType());
        event.setTrafficCondition(classify(measurement));
        event.setTrafficEnrichmentStatus(TrafficEnrichmentStatus.ENRICHED);
        event.setTrafficEnriched(true);
    }

    private TrafficCondition classify(TrafficMeasurement measurement) {
        Integer volume = measurement.volumeKfz();
        Double speed = measurement.speedKfz();
        if (volume == null && speed == null) {
            return TrafficCondition.UNKNOWN;
        }
        if (speed != null && speed <= congestedSpeedKmh && volume != null && volume > lightVolumeThreshold) {
            return TrafficCondition.CONGESTED;
        }
        if ((speed != null && speed <= lowSpeedKmh) || (volume != null && volume >= heavyVolumeThreshold)) {
            return TrafficCondition.HEAVY;
        }
        if (volume != null && volume <= lightVolumeThreshold && (speed == null || speed > lowSpeedKmh)) {
            return TrafficCondition.LIGHT;
        }
        return TrafficCondition.MODERATE;
    }

    private void markMissing(SegmentEvent event, TrafficEnrichmentStatus status) {
        if (event == null) {
            return;
        }
        event.setTrafficVolumeKfz(null);
        event.setTrafficSpeedKfz(null);
        event.setTrafficVolumePkw(null);
        event.setTrafficSpeedPkw(null);
        event.setTrafficVolumeLkw(null);
        event.setTrafficSpeedLkw(null);
        event.setTrafficSourceType(null);
        event.setTrafficCondition(null);
        event.setTrafficEnrichmentStatus(status);
        event.setTrafficEnriched(true);
    }

    private boolean streetMatches(String segmentStreet, String detectorStreet) {
        if (isBlank(segmentStreet) || isBlank(detectorStreet) || "unknown".equalsIgnoreCase(segmentStreet)) {
            return false;
        }
        String segment = normalizeStreet(segmentStreet);
        String detector = normalizeStreet(detectorStreet);
        return segment.contains(detector) || detector.contains(segment);
    }

    private String normalizeStreet(String street) {
        return street.toLowerCase(Locale.GERMAN)
                .replace("str.", "straße")
                .replace("strasse", "straße")
                .trim();
    }

    private Double directionBearing(String direction) {
        if (isBlank(direction)) {
            return null;
        }
        return switch (direction.trim().toLowerCase(Locale.GERMAN)) {
            case "nord" -> 0.0;
            case "nordost" -> 45.0;
            case "ost" -> 90.0;
            case "südost", "suedost" -> 135.0;
            case "süd", "sued" -> 180.0;
            case "südwest", "suedwest" -> 225.0;
            case "west" -> 270.0;
            case "nordwest" -> 315.0;
            default -> null;
        };
    }

    private double bearingDelta(double first, double second) {
        return Math.abs(((first - second + 540.0) % 360.0) - 180.0);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record LookupOutcome(Optional<TrafficMeasurement> measurement, TrafficEnrichmentStatus status) {

        static LookupOutcome enriched(TrafficMeasurement measurement) {
            return new LookupOutcome(Optional.of(measurement), TrafficEnrichmentStatus.ENRICHED);
        }

        static LookupOutcome missing(TrafficEnrichmentStatus status) {
            return new LookupOutcome(Optional.empty(), status);
        }
    }
}
