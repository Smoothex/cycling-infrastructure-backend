package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.BikeType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

@Service
public class RideIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(RideIntentClassifier.class);
    private static final ZoneId BERLIN_ZONE = ZoneId.of("Europe/Berlin");
    private static final int CONFIDENCE_THRESHOLD = 2;

    public void classify(Ride ride) {
        if (ride.getStatus() != Status.PROCESSED && ride.getStatus() != Status.ALTERNATIVE_ROUTE) {
            ride.setRideIntent(RideIntent.UNKNOWN);
            return;
        }

        int score = 0;

        // Time signals
        if (ride.getStartTime() != null && ride.getEndTime() != null) {
            Instant startInstant = Instant.ofEpochMilli(ride.getStartTime());
            Instant endInstant = Instant.ofEpochMilli(ride.getEndTime());
            ZonedDateTime start = startInstant.atZone(BERLIN_ZONE);
            DayOfWeek dow = start.getDayOfWeek();
            int hour = start.getHour();
            int minute = start.getMinute();

            boolean isWeekend = (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY);

            if (isWeekend) {
                score -= 2;
            } else {
                // 06:00-09:00
                boolean morningPeak = hour >= 6 && hour < 9;
                // 17:00-19:30
                boolean eveningPeak = (hour == 17 || hour == 18 || hour == 19 && minute <= 30);

                if (morningPeak || eveningPeak) {
                    score += 2;
                } else {
                    score += 1; // other weekday time probably also commute, but less confident
                }
            }

            // Duration
            long durationMinutes = ChronoUnit.MINUTES.between(startInstant, endInstant);
            if (durationMinutes >= 15 && durationMinutes <= 50) {
                score += 1;
            } else if (durationMinutes > 50) {
                score -= 1;
            }

            // Average speed
            if (ride.getActualDistance() != null) {
                long durationSeconds = ChronoUnit.SECONDS.between(startInstant, endInstant);
                if (durationSeconds > 0) {
                    double avgSpeedKmh = (ride.getActualDistance() / durationSeconds) * 3.6;
                    if (avgSpeedKmh >= 12.0 && avgSpeedKmh <= 22.0) {
                        score += 1; // urban commute pace
                    } else if (avgSpeedKmh < 10.0) {
                        score -= 1; // slow pace
                    } else if (avgSpeedKmh > 28.0) {
                        score -= 1; // sport pace
                    }
                }
            }
        }

        // Route directness
        if (Boolean.FALSE.equals(ride.getIsDetour())) {
            score += 1;
        } else if (Boolean.TRUE.equals(ride.getIsDetour())) {
            score -= 1;
        }

        if (ride.getStatus() == Status.ALTERNATIVE_ROUTE) {
            score -= 2;
        }

        if (ride.getOverlapRatio() != null && ride.getOverlapRatio() >= 0.85) {
            score += 1;
        }

        if (ride.getActualDistance() != null && ride.getShortestPathDistance() != null
                && ride.getShortestPathDistance() > 0) {
            double distRatio = ride.getActualDistance() / ride.getShortestPathDistance();
            if (distRatio <= 1.15) {
                score += 1;
            } else if (distRatio >= 1.50) {
                score -= 2;
            }
        }

        // Equipment
        if (Boolean.TRUE.equals(ride.getChildTransport())) {
            score += 1; // probably less likely to be leisure with a child seat
        }

        if (Boolean.TRUE.equals(ride.getTrailerAttached())) {
            score -= 1; // no leisure with trailer
        }

        BikeType bikeType = ride.getBikeType();
        if (bikeType != null) {
            switch (bikeType) {
                case ROAD_RACING_BIKE, MOUNTAIN_BIKE -> score -= 1;
                case CITY_TREKKING_BIKE, E_BIKE, FREIGHT_BICYCLE -> score += 1;
                default -> { /* neutral */ }
            }
        }

        // Check against threshold
        RideIntent intent;
        if (score >= CONFIDENCE_THRESHOLD) {
            intent = RideIntent.COMMUTE;
        } else if (score <= -CONFIDENCE_THRESHOLD) {
            intent = RideIntent.LEISURE;
        } else {
            intent = RideIntent.UNKNOWN;
        }

        log.debug("Ride {} classified as {} (score={})", ride.getId(), intent, score);
        ride.setRideIntent(intent);
    }
}
