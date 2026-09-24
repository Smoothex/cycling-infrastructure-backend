package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.Traffic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * LRU cache of parsed traffic files, bounded by estimated retained bytes.
 * The estimate includes map entries, keys, measurements and boxed numeric values;
 * it is not a JVM heap measurement. A file is parsed before admission, so the
 * temporary parsing allocation is outside this limit. Loaders must return an
 * owned map that they will not subsequently mutate.
 */
final class TrafficMeasurementCache {

    private static final Logger log = LoggerFactory.getLogger(TrafficMeasurementCache.class);
    private static final long FILE_OVERHEAD_BYTES = 1024;
    private static final long ROW_OVERHEAD_BYTES = 512;

    private final long maxBytes;
    private final LinkedHashMap<Path, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long retainedBytes;

    TrafficMeasurementCache(long maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("Traffic parsed-cache memory limit must not be negative");
        }
        this.maxBytes = maxBytes;
    }

    // Serialize misses as well as eviction: concurrent callers must not parse
    // multiple large files simultaneously or load the same file more than once.
    synchronized Map<String, TrafficMeasurement> get(
            Path path, Function<Path, Map<String, TrafficMeasurement>> loader) {
        Path key = path.toAbsolutePath().normalize();
        Entry cached = entries.get(key);
        if (cached != null) {
            return cached.rows();
        }

        Map<String, TrafficMeasurement> rows = Collections.unmodifiableMap(loader.apply(key));
        long weight = estimatedBytes(key, rows);
        if (weight > maxBytes) {
            log.debug("Traffic file '{}' not retained: estimated bytes={} exceeds cache limit={}",
                    key, weight, maxBytes);
            return rows;
        }

        while (retainedBytes > maxBytes - weight) {
            var evicted = entries.pollFirstEntry();
            retainedBytes -= evicted.getValue().weight();
            log.debug("Evicted parsed traffic file '{}' from memory", evicted.getKey());
        }
        entries.put(key, new Entry(rows, weight));
        retainedBytes += weight;
        return rows;
    }

    private static long estimatedBytes(Path path, Map<String, TrafficMeasurement> rows) {
        // Charge even empty files, so caching many empty maps is also bounded.
        long bytes = FILE_OVERHEAD_BYTES + 2L * path.toString().length();
        for (String key : rows.keySet()) {
            bytes += ROW_OVERHEAD_BYTES + 2L * key.length();
        }
        return bytes;
    }

    private record Entry(Map<String, TrafficMeasurement> rows, long weight) {}
}
