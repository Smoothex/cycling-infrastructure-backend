package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Incident;
import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.RidePoint;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.BikeType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.IncidentType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ParticipantType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.PhoneLocation;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
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

            // We use OpenCSV's CSVReader but control the iteration manually to handle the multi-section format
            // Configure parser to handle the standard simra CSV format
            CSVParser parser = new CSVParserBuilder()
                    .withSeparator(',')
                    .withIgnoreQuotations(true)
                    .build();

            CSVReader csvReader = new CSVReaderBuilder(br)
                    .withCSVParser(parser)
                    .build();

            String[] line;
            boolean processingIncidents = true;
            boolean incidentHeaderFound = false;
            boolean pointHeaderFound = false;

            List<RidePoint> points = new ArrayList<>();
            int sequence = 0;

            try {
                while ((line = csvReader.readNext()) != null) {
                    // Check for empty lines or comments structure
                    if (line.length == 0 || (line.length == 1 && line[0].trim().isEmpty())) {
                        continue;
                    }

                    // Check for section separator
                    if (line.length == 1 && line[0].startsWith("======")) {
                        processingIncidents = false;
                        continue;
                    }

                    // Check for file version header (e.g., 58#2) - usually length 1
                    if (line.length == 1 && line[0].contains("#")) {
                        continue;
                    }

                    if (processingIncidents) {
                        // Detect Incident Header
                        if (!incidentHeaderFound) {
                            if (line.length > 0 && line[0].equals("key")) {
                                incidentHeaderFound = true;
                            }
                            continue;
                        }

                        // Parse Incident Row
                        parseIncidentRow(ride, line);

                    } else {
                        // Detect Point Header
                        if (!pointHeaderFound) {
                            if (line.length > 1 && line[0].equals("lat") && line[1].equals("lon")) {
                                pointHeaderFound = true;
                            }
                            continue;
                        }

                        // Parse Point Row
                        RidePoint point = parseRidePointRow(line, sequence++);
                        if (point != null) {
                            point.setRide(ride);
                            points.add(point);
                        }
                    }
                }
            } catch (CsvValidationException e) {
                throw new IOException("CSV validation error", e);
            }

            ride.setRidePoints(points);
            addPointsToRide(ride, points);

            return ride;
        }
    }

    private void addPointsToRide(Ride ride, List<RidePoint> points) {
        if (!points.isEmpty()) {
            ride.setStartTime(points.get(0).getTimestamp());
            ride.setEndTime(points.get(points.size() - 1).getTimestamp());

            Coordinate[] coordinates = points.stream()
                    .filter(p -> p.getLocation() != null)
                    .map(p -> p.getLocation().getCoordinate())
                    .toArray(Coordinate[]::new);

            if (coordinates.length >= 2) {
                ride.setTrajectory(geometryFactory.createLineString(coordinates));
            }
        }
    }

    private void parseIncidentRow(Ride ride, String[] parts) {
        if (parts.length < 9) return;

        try {
            // Metadata extraction (lazy loading from first valid row)
            if (ride.getBikeType() == null) {
                if (!parts[4].isEmpty()) ride.setBikeType(BikeType.fromCode(Integer.parseInt(parts[4])));
                ride.setChildTransport("1".equals(parts[5]));
                ride.setTrailerAttached("1".equals(parts[6]));
                if (!parts[7].isEmpty()) ride.setPhoneLocation(PhoneLocation.fromCode(Integer.parseInt(parts[7])));
            }


            int incidentTypeCode = Integer.parseInt(parts[8]);
            // Skip dummy incidents
            if (incidentTypeCode == -5) return;

            Incident incident = new Incident();
            incident.setRide(ride);
            incident.setIncidentKey(Integer.parseInt(parts[0]));

            if (!parts[1].isEmpty() && !parts[2].isEmpty()) {
                incident.setLocation(geometryFactory.createPoint(new Coordinate(
                        Double.parseDouble(parts[2]), Double.parseDouble(parts[1]))));
            }

            if (!parts[3].isEmpty()) incident.setTimestamp(Long.parseLong(parts[3]));
            incident.setIncidentType(IncidentType.fromCode(incidentTypeCode));

            // Participants logic
            Set<ParticipantType> participants = new HashSet<>();
            if (parts.length > 9 && "1".equals(parts[9])) participants.add(ParticipantType.BUS);
            if (parts.length > 10 && "1".equals(parts[10])) participants.add(ParticipantType.CYCLIST);
            if (parts.length > 11 && "1".equals(parts[11])) participants.add(ParticipantType.PEDESTRIAN);
            if (parts.length > 12 && "1".equals(parts[12])) participants.add(ParticipantType.DELIVERY_VAN);
            if (parts.length > 13 && "1".equals(parts[13])) participants.add(ParticipantType.TRUCK);
            if (parts.length > 14 && "1".equals(parts[14])) participants.add(ParticipantType.MOTORCYCLE);
            if (parts.length > 15 && "1".equals(parts[15])) participants.add(ParticipantType.CAR);
            if (parts.length > 16 && "1".equals(parts[16])) participants.add(ParticipantType.TAXI);
            if (parts.length > 17 && "1".equals(parts[17])) participants.add(ParticipantType.OTHER);
            if (parts.length > 20 && "1".equals(parts[20])) participants.add(ParticipantType.SCOOTER);

            incident.setInvolvedParticipants(participants);

            if (parts.length > 18) incident.setScary("1".equals(parts[18]));
            if (parts.length > 19) incident.setDescription(parts[19]);

            ride.getIncidents().add(incident);
        } catch (NumberFormatException ignored) { }
    }

    private RidePoint parseRidePointRow(String[] parts, int sequence) {
        if (parts.length < 6) return null;

        RidePoint point = new RidePoint();
        point.setSequenceIndex(sequence);

        try {
            if (!parts[0].isEmpty() && !parts[1].isEmpty()) {
                point.setLocation(geometryFactory.createPoint(new Coordinate(
                        Double.parseDouble(parts[1]), Double.parseDouble(parts[0]))));
            }

            if (!parts[2].isEmpty()) point.setX(Double.parseDouble(parts[2]));
            if (!parts[3].isEmpty()) point.setY(Double.parseDouble(parts[3]));
            if (!parts[4].isEmpty()) point.setZ(Double.parseDouble(parts[4]));
            if (!parts[5].isEmpty()) point.setTimestamp(Long.parseLong(parts[5]));
            if (parts.length > 6 && !parts[6].isEmpty()) point.setGpsAccuracy(Double.parseDouble(parts[6]));

            if (parts.length > 9) {
                if (!parts[7].isEmpty()) point.setA(Double.parseDouble(parts[7]));
                if (!parts[8].isEmpty()) point.setB(Double.parseDouble(parts[8]));
                if (!parts[9].isEmpty()) point.setC(Double.parseDouble(parts[9]));
            }

            return point;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
