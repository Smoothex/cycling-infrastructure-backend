package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import java.io.IOException;
import java.nio.file.Path;

/** Validates a downloaded snapshot before it is admitted to the immutable local cache. */
public interface OhsomeSnapshotValidator {

    OhsomeSnapshotMetadata validate(Path snapshot) throws IOException;
}
