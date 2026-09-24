package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.Traffic;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TrafficMeasurementCacheTest {

    @TempDir
    Path directory;

    private final TrafficMeasurement measurement = new TrafficMeasurement(
            TrafficSourceType.NEW_DETECTOR, 100, 30.0, 90, 31.0, 10, 25.0, null, 100.0);

    @Test
    void evictsLeastRecentlyUsedFileAndReloadsItFromDisk() throws Exception {
        var cache = new TrafficMeasurementCache(4000); // Room for two one-row files.
        Path a = Files.writeString(directory.resolve("a"), "2024-01-01|0");
        Path b = Files.writeString(directory.resolve("b"), "2024-01-01|1");
        Path c = Files.writeString(directory.resolve("c"), "2024-01-01|2");
        Map<Path, Integer> loads = new HashMap<>();
        Function<Path, Map<String, TrafficMeasurement>> loader = path -> {
            loads.merge(path, 1, Integer::sum);
            try {
                return Map.of(Files.readString(path), measurement);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };

        var first = cache.get(a, loader);
        cache.get(b, loader);
        assertSame(first, cache.get(a, loader)); // A is now most recently used.
        cache.get(c, loader); // B must be evicted, not A.
        assertSame(first, cache.get(a, loader));
        assertEquals(measurement, cache.get(b, loader).get("2024-01-01|1"));
        assertEquals(1, loads.get(a));
        assertEquals(2, loads.get(b));
        assertEquals(1, loads.get(c));
        assertTrue(Files.exists(a));
        assertTrue(Files.exists(b));
        assertTrue(Files.exists(c));
    }

    @Test
    void largerFilesConsumeMoreOfTheBudget() {
        var cache = new TrafficMeasurementCache(6000);
        AtomicInteger smallLoads = new AtomicInteger();
        Function<Path, Map<String, TrafficMeasurement>> small = path -> {
            smallLoads.incrementAndGet();
            return rows(1);
        };
        cache.get(directory.resolve("a"), small);
        cache.get(directory.resolve("b"), small);
        cache.get(directory.resolve("large"), path -> rows(8));
        // Eight rows fit alone, but cannot share this budget with a small file.
        cache.get(directory.resolve("a"), small);
        assertEquals(3, smallLoads.get());
    }

    @Test
    void oversizedFileIsReturnedButNotRetainedOrAllowedToEvictUsefulEntries() {
        var cache = new TrafficMeasurementCache(4000);
        var small = cache.get(directory.resolve("small"), path -> rows(1));
        AtomicInteger largeLoads = new AtomicInteger();
        Function<Path, Map<String, TrafficMeasurement>> loader = path -> {
            largeLoads.incrementAndGet();
            return rows(100);
        };
        assertEquals(100, cache.get(directory.resolve("large"), loader).size());
        assertEquals(100, cache.get(directory.resolve("large"), loader).size());
        assertEquals(2, largeLoads.get());
        assertSame(small, cache.get(directory.resolve("small"), path -> fail("Small entry was evicted")));
    }

    @Test
    void emptyFilesAlsoConsumeBudget() {
        var cache = new TrafficMeasurementCache(2500); // Two empty files, not three.
        AtomicInteger loads = new AtomicInteger();
        Function<Path, Map<String, TrafficMeasurement>> loader = path -> {
            loads.incrementAndGet();
            return Map.of();
        };
        cache.get(directory.resolve("a"), loader);
        cache.get(directory.resolve("b"), loader);
        cache.get(directory.resolve("c"), loader);
        cache.get(directory.resolve("a"), loader);
        assertEquals(4, loads.get());
    }

    @Test
    void zeroBudgetDisablesRetention() {
        var cache = new TrafficMeasurementCache(0);
        AtomicInteger loads = new AtomicInteger();
        Function<Path, Map<String, TrafficMeasurement>> loader = path -> {
            loads.incrementAndGet();
            return rows(1);
        };
        cache.get(directory.resolve("a"), loader);
        cache.get(directory.resolve("a"), loader);
        assertEquals(2, loads.get());
    }

    @Test
    void failedLoadsCanBeRetriedWithoutLosingCachedFiles() {
        var cache = new TrafficMeasurementCache(4000);
        var existing = cache.get(directory.resolve("a"), path -> rows(1));
        Path failing = directory.resolve("b");
        assertThrows(IllegalStateException.class, () -> cache.get(failing, path -> {
            throw new IllegalStateException("Incomplete archive");
        }));
        assertSame(existing, cache.get(directory.resolve("a"), path -> fail("Existing entry lost")));
        assertEquals(1, cache.get(failing, path -> rows(1)).size());
    }

    @Test
    void concurrentRequestsLoadFileOnlyOnce() throws Exception {
        var cache = new TrafficMeasurementCache(4000);
        var start = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var requests = IntStream.range(0, 4).mapToObj(i -> executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return cache.get(directory.resolve("a"), path -> {
                    loads.incrementAndGet();
                    return rows(1);
                });
            })).toList();
            start.countDown();
            var first = requests.getFirst().get(5, TimeUnit.SECONDS);
            for (var request : requests) {
                assertSame(first, request.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, loads.get());
        }
    }

    @Test
    void rejectsNegativeBudget() {
        assertThrows(IllegalArgumentException.class, () -> new TrafficMeasurementCache(-1));
    }

    private Map<String, TrafficMeasurement> rows(int count) {
        Map<String, TrafficMeasurement> rows = new HashMap<>();
        for (int i = 0; i < count; i++) {
            rows.put("2024-01-01|" + i, measurement);
        }
        return rows;
    }
}
