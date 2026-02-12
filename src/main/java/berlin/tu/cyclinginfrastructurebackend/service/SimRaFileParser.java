package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Incident;
import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.RidePoint;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.BikeType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.IncidentType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ParticipantType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.PhoneLocation;
import de.siegmar.fastcsv.reader.CsvRecord;
import de.siegmar.fastcsv.reader.CsvReader;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;

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

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    public Ride parse(InputStream inputStream, String filename) throws IOException {
        Ride ride = new Ride();
        ride.setOriginalFilename(filename);

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            // FastCSV reader
            CsvReader<CsvRecord> csvReader = CsvReader.builder().ofCsvRecord(br);

            boolean processingIncidents = true;
            boolean incidentHeaderFound = false;
            boolean pointHeaderFound = false;

            List<RidePoint> points = new ArrayList<>();
            int sequence = 0;

            for (CsvRecord record : csvReader) {
                // Check for empty lines or single column weirdness
                if (record.getFieldCount() == 0) continue;
                String firstField = record.getField(0);

                if (firstField.trim().isEmpty() && record.getFieldCount() == 1) continue;

                // Check for section separator
                if (firstField.startsWith("======")) {
                    processingIncidents = false;
                    continue;
                }

                // Check for file version header (e.g., 58#2)
                if (record.getFieldCount() == 1 && firstField.contains("#")) {
                    continue;
                }

                if (processingIncidents) {
                    // Detect Incident Header
                    if (!incidentHeaderFound) {
                        if ("key".equals(firstField)) {
                            incidentHeaderFound = true;
                        }
                        continue;
                    }

                    // Parse Incident Row
                    parseIncidentRow(ride, record);

                } else {
                    // Detect Point Header
                    if (!pointHeaderFound) {
                        if (record.getFieldCount() > 1 && "lat".equals(firstField) && "lon".equals(record.getField(1))) {
                            pointHeaderFound = true;
                        }
                        continue;
                    }

                    // Parse Point Row
                    RidePoint point = parseRidePointRow(record, sequence++);
                    if (point != null) {
                        point.setRide(ride);
                        points.add(point);
                    }
                }
            }

            ride.setRidePoints(points);
            addPointsToRide(ride, points);

            return ride;
        }
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

    private void parseIncidentRow(Ride ride, CsvRecord record) {
        if (record.getFieldCount() < 9) return;

        try {
            if (ride.getBikeType() == null) {
                String bikeStr = record.getField(4);
                if (!bikeStr.isEmpty()) ride.setBikeType(BikeType.fromCode(Integer.parseInt(bikeStr)));
                ride.setChildTransport("1".equals(record.getField(5)));
                ride.setTrailerAttached("1".equals(record.getField(6)));
                String pLocStr = record.getField(7);
                if (!pLocStr.isEmpty()) ride.setPhoneLocation(PhoneLocation.fromCode(Integer.parseInt(pLocStr)));
            }

            String incTypeStr = record.getField(8);
            if (incTypeStr.isEmpty()) return;

            int incidentTypeCode = Integer.parseInt(incTypeStr);
            // Skip dummy incidents
            if (incidentTypeCode == -5) return;

            Incident incident = new Incident();
            incident.setRide(ride);
            incident.setIncidentKey(Integer.parseInt(record.getField(0)));

            String latStr = record.getField(1);
            String lonStr = record.getField(2);
            if (!latStr.isEmpty() && !lonStr.isEmpty()) {
                incident.setLocation(geometryFactory.createPoint(new Coordinate(
                        Double.parseDouble(lonStr), Double.parseDouble(latStr))));
            }

            String tsStr = record.getField(3);
            if (!tsStr.isEmpty()) incident.setTimestamp(Long.parseLong(tsStr));
            incident.setIncidentType(IncidentType.fromCode(incidentTypeCode));

            // Participants logic
            Set<ParticipantType> participants = new HashSet<>();
            checkParticipant(participants, record, 9, ParticipantType.BUS);
            checkParticipant(participants, record, 10, ParticipantType.CYCLIST);
            checkParticipant(participants, record, 11, ParticipantType.PEDESTRIAN);
            checkParticipant(participants, record, 12, ParticipantType.DELIVERY_VAN);
            checkParticipant(participants, record, 13, ParticipantType.TRUCK);
            checkParticipant(participants, record, 14, ParticipantType.MOTORCYCLE);
            checkParticipant(participants, record, 15, ParticipantType.CAR);
            checkParticipant(participants, record, 16, ParticipantType.TAXI);
            checkParticipant(participants, record, 17, ParticipantType.OTHER);
            checkParticipant(participants, record, 20, ParticipantType.SCOOTER);

            incident.setInvolvedParticipants(participants);

            if (record.getFieldCount() > 18) incident.setScary("1".equals(record.getField(18)));
            if (record.getFieldCount() > 19) incident.setDescription(record.getField(19));

            ride.getIncidents().add(incident);
        } catch (NumberFormatException ignored) { }
    }

    private void checkParticipant(Set<ParticipantType> participants, CsvRecord record, int index, ParticipantType type) {
        if (record.getFieldCount() > index && "1".equals(record.getField(index))) {
            participants.add(type);
        }
    }

    private RidePoint parseRidePointRow(CsvRecord record, int sequence) {
        if (record.getFieldCount() < 6) return null;

        RidePoint point = new RidePoint();
        point.setSequenceIndex(sequence);

        try {
            String latStr = record.getField(0);
            String lonStr = record.getField(1);

            if (!latStr.isEmpty() && !lonStr.isEmpty()) {
                point.setLocation(geometryFactory.createPoint(new Coordinate(
                        Double.parseDouble(lonStr), Double.parseDouble(latStr))));
            }

            String xStr = record.getField(2);
            if (!xStr.isEmpty()) point.setX(Double.parseDouble(xStr));

            String yStr = record.getField(3);
            if (!yStr.isEmpty()) point.setY(Double.parseDouble(yStr));

            String zStr = record.getField(4);
            if (!zStr.isEmpty()) point.setZ(Double.parseDouble(zStr));

            String tsStr = record.getField(5);
            if (!tsStr.isEmpty()) point.setTimestamp(Long.parseLong(tsStr));

            if (record.getFieldCount() > 6) {
                String accStr = record.getField(6);
                if (!accStr.isEmpty()) point.setGpsAccuracy(Double.parseDouble(accStr));
            }

            if (record.getFieldCount() > 9) {
                String aStr = record.getField(7);
                if (!aStr.isEmpty()) point.setA(Double.parseDouble(aStr));

                String bStr = record.getField(8);
                if (!bStr.isEmpty()) point.setB(Double.parseDouble(bStr));

                String cStr = record.getField(9);
                if (!cStr.isEmpty()) point.setC(Double.parseDouble(cStr));
            }

            return point;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
