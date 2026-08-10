package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

/** Metadata verified directly from an ohsome GeoParquet snapshot. */
public record OhsomeSnapshotMetadata(
        long featureCount,
        String apiVersion,
        String geoParquetVersion,
        String crsIdentifier
) {
}
