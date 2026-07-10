package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

import berlin.tu.cyclinginfrastructurebackend.domain.Incident;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.IncidentType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ParticipantType;

import java.util.List;
import java.util.UUID;

public record IncidentDto(
        UUID id,
        double lon,
        double lat,
        Long timestamp,
        IncidentType incidentType,
        boolean scary,
        String description,
        List<ParticipantType> involvedParticipants
) {

    public static IncidentDto from(Incident incident) {
        return new IncidentDto(
                incident.getId(),
                incident.getLocation().getX(),
                incident.getLocation().getY(),
                incident.getTimestamp(),
                incident.getIncidentType(),
                Boolean.TRUE.equals(incident.getScary()),
                incident.getDescription(),
                incident.getInvolvedParticipants().stream().sorted().toList()
        );
    }
}
