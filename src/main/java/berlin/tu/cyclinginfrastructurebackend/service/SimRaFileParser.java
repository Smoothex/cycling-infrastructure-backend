package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Incident;
import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.RidePoint;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.BikeType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.IncidentType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ParticipantType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.PhoneLocation;
import berlin.tu.cyclinginfrastructurebackend.service.dto.IncidentCsvBean;
import berlin.tu.cyclinginfrastructurebackend.service.dto.RidePointCsvBean;
import com.opencsv.bean.CsvToBeanBuilder;
import org.jspecify.annotations.NonNull;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class SimRaFileParser {

    private static final Logger log = LoggerFactory.getLogger(SimRaFileParser.class);

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    public Ride parse(InputStream inputStream, String filename) throws IOException {
        Ride ride = new Ride();
        ride.setOriginalFilename(filename);

        List<String> incidentLines = new ArrayList<>();
        List<String> rideLines = new ArrayList<>();
        boolean separatorFound = false;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("======")) {
                    separatorFound = true;
                    continue; // Skip the separator itself
                }

                if (line.trim().isEmpty()) continue;

                if (!separatorFound) {
                    incidentLines.add(line);
                } else {
                    rideLines.add(line);
                }
            }
        }

        if (incidentLines.isEmpty() && rideLines.isEmpty()) {
            throw new IOException("Invalid file format: file is empty or contains no data");
        }

        parseIncidents(ride, incidentLines);
        parseRidePoints(ride, rideLines);

        return ride;
    }

    private void parseIncidents(Ride ride, List<String> lines) {
        List<IncidentCsvBean> beans = mapLinesToBeans(lines, "key,", IncidentCsvBean.class);

        List<Incident> incidents = new ArrayList<>();
        for (IncidentCsvBean bean : beans) {
           Incident incident = mapToIncident(bean, ride);
           if (incident != null) {
               incidents.add(incident);
           }

           // Extract ride metadata from the first valid incident row (often row 0 has metadata)
           if (bean.getBike() != null && ride.getBikeType() == null) {
               ride.setBikeType(BikeType.fromCode(bean.getBike()));
               ride.setChildTransport(Boolean.TRUE.equals(bean.getChildCheckBox()));
               ride.setTrailerAttached(Boolean.TRUE.equals(bean.getTrailerCheckBox()));
               if (bean.getPLoc() != null) {
                   ride.setPhoneLocation(PhoneLocation.fromCode(bean.getPLoc()));
               }
           }
        }
        ride.setIncidents(incidents);
    }

    private void parseRidePoints(Ride ride, List<String> lines) {
        List<RidePointCsvBean> beans = mapLinesToBeans(lines, "lat,", RidePointCsvBean.class);

        List<RidePoint> points = new ArrayList<>();
        int sequence = 0;
        for (RidePointCsvBean bean : beans) {
            RidePoint point = mapToRidePoint(bean, sequence++, ride);
            points.add(point);
        }
        ride.setRidePoints(points);
        addPointsToRide(ride, points);
    }

    private <T> List<T> mapLinesToBeans(List<String> lines, String headerMarker, Class<T> type) {
        if (lines == null || lines.isEmpty()) return new ArrayList<>();

        int headerIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(headerMarker)) {
                headerIndex = i;
                break;
            }
        }

        if (headerIndex == -1) {
            return new ArrayList<>(); // Header not found in this section
        }

        StringBuilder cleanCsvBuffer = sanitizeCsv(lines, headerIndex);

        try {
            return new CsvToBeanBuilder<T>(new java.io.StringReader(cleanCsvBuffer.toString()))
                    .withType(type)
                    .withIgnoreLeadingWhiteSpace(true)
                    .withIgnoreEmptyLine(true)
                    .withThrowExceptions(false)
                    .build()
                    .parse();
        } catch (RuntimeException e) {
            log.error("CSV Parsing failed for type {}: {}", type.getSimpleName(), e.getMessage());
            return new ArrayList<>();
        }
    }

    private static @NonNull StringBuilder sanitizeCsv(List<String> lines, int headerIndex) {
        String header = lines.get(headerIndex);
        int expectedCols = countCommas(header) + 1;

        StringBuilder cleanCsvBuffer = new StringBuilder();
        for (int i = headerIndex; i < lines.size(); i++) {
            String line = lines.get(i);
            int currentCols = countCommas(line) + 1;

            cleanCsvBuffer.append(line);
            if (currentCols < expectedCols) {
                cleanCsvBuffer.append(",".repeat(expectedCols - currentCols));  // padding
            }
            cleanCsvBuffer.append("\n");
        }
        return cleanCsvBuffer;
    }

    private static int countCommas(String str) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == ',') {
                count++;
            }
        }
        return count;
    }

    private Incident mapToIncident(IncidentCsvBean bean, Ride ride) {
        if (bean.getIncident() == null) return null;
        // -5 is often used as a dummy or 'nothing' placeholder in SimRa
        if (bean.getIncident() == -5) return null;

        Incident incident = new Incident();
        incident.setRide(ride);
        incident.setIncidentKey(bean.getKey());

        if (bean.getLat() != null && bean.getLon() != null) {
            incident.setLocation(geometryFactory.createPoint(new Coordinate(bean.getLon(), bean.getLat())));
        }
        incident.setTimestamp(bean.getTs());
        incident.setIncidentType(IncidentType.fromCode(bean.getIncident()));
        incident.setScary(Boolean.TRUE.equals(bean.getScary()));
        incident.setDescription(bean.getDesc());

        Set<ParticipantType> participants = new HashSet<>();
        if (Boolean.TRUE.equals(bean.getI1())) participants.add(ParticipantType.BUS);
        if (Boolean.TRUE.equals(bean.getI2())) participants.add(ParticipantType.CYCLIST);
        if (Boolean.TRUE.equals(bean.getI3())) participants.add(ParticipantType.PEDESTRIAN);
        if (Boolean.TRUE.equals(bean.getI4())) participants.add(ParticipantType.DELIVERY_VAN);
        if (Boolean.TRUE.equals(bean.getI5())) participants.add(ParticipantType.TRUCK);
        if (Boolean.TRUE.equals(bean.getI6())) participants.add(ParticipantType.MOTORCYCLE);
        if (Boolean.TRUE.equals(bean.getI7())) participants.add(ParticipantType.CAR);
        if (Boolean.TRUE.equals(bean.getI8())) participants.add(ParticipantType.TAXI);
        if (Boolean.TRUE.equals(bean.getI9())) participants.add(ParticipantType.OTHER);
        if (Boolean.TRUE.equals(bean.getI10())) participants.add(ParticipantType.SCOOTER);

        incident.setInvolvedParticipants(participants);
        return incident;
    }

    private RidePoint mapToRidePoint(RidePointCsvBean bean, int sequence, Ride ride) {
        RidePoint point = new RidePoint();
        point.setRide(ride);
        point.setSequenceIndex(sequence);
        point.setTimestamp(bean.getTimeStamp());

        if (bean.getLat() != null && bean.getLon() != null) {
            point.setLocation(geometryFactory.createPoint(new Coordinate(bean.getLon(), bean.getLat())));
        }

        point.setX(bean.getX());
        point.setY(bean.getY());
        point.setZ(bean.getZ());
        point.setGpsAccuracy(bean.getAcc());
        point.setA(bean.getA());
        point.setB(bean.getB());
        point.setC(bean.getC());

        return point;
    }

    private void addPointsToRide(Ride ride, List<RidePoint> points) {
        if (!points.isEmpty()) {
            ride.setStartTime(points.getFirst().getTimestamp());
            ride.setEndTime(points.getLast().getTimestamp());

            Coordinate[] coordinates = points.stream()
                    .filter(p -> p.getLocation() != null)
                    .map(p -> p.getLocation().getCoordinate())
                    .toArray(Coordinate[]::new);

            if (coordinates.length >= 2) {
                ride.setTrajectory(geometryFactory.createLineString(coordinates));
            }
        }
    }
}
