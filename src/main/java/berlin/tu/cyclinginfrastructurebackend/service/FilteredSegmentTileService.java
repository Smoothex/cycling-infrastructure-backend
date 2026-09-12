package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.repository.FilteredSegmentTileRepository;
import berlin.tu.cyclinginfrastructurebackend.service.dto.SegmentTileFilter;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Bounded LRU cache with single-flight loads and bounded database concurrency. */
@Service
public class FilteredSegmentTileService {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ENTRIES = 256;
    private static final long TTL_NANOS = Duration.ofMinutes(5).toNanos();
    private final FilteredSegmentTileRepository repository;
    private final TileBuildService tileBuildService;
    private final LinkedHashMap<Key, Entry> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final ConcurrentHashMap<Key, CompletableFuture<byte[]>> loading = new ConcurrentHashMap<>();
    private final Semaphore queries = new Semaphore(4);
    private long cacheBytes;

    public FilteredSegmentTileService(FilteredSegmentTileRepository repository, TileBuildService tileBuildService) {
        this.repository = repository;
        this.tileBuildService = tileBuildService;
    }

    public byte[] tile(int z, int x, int y, SegmentTileFilter filter) {
        Key key = new Key(z, x, y, filter, tileBuildService.dataVersion());
        byte[] cached = cached(key);
        if (cached != null) return cached;
        CompletableFuture<byte[]> pending = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = loading.putIfAbsent(key, pending);
        if (existing != null) return existing.join();
        try {
            // A previous load may have completed between the cache lookup and single-flight registration.
            byte[] result = cached(key);
            if (result == null) {
                queries.acquireUninterruptibly();
                try {
                    result = repository.tile(z, x, y, filter);
                } finally {
                    queries.release();
                }
                remember(key, result);
            }
            pending.complete(result);
            return result;
        } catch (RuntimeException | Error exception) {
            pending.completeExceptionally(exception);
            throw exception;
        } finally {
            loading.remove(key, pending);
        }
    }

    private synchronized byte[] cached(Key key) {
        Entry entry = cache.get(key);
        if (entry == null) return null;
        if (System.nanoTime() - entry.createdAt() >= TTL_NANOS) {
            cache.remove(key);
            cacheBytes -= entry.bytes().length;
            return null;
        }
        return entry.bytes();
    }

    private synchronized void remember(Key key, byte[] bytes) {
        if (bytes.length > MAX_BYTES || key.version() != tileBuildService.dataVersion()) return;
        cache.entrySet().removeIf(entry -> {
            boolean expired = entry.getKey().version() != key.version()
                    || System.nanoTime() - entry.getValue().createdAt() >= TTL_NANOS;
            if (expired) cacheBytes -= entry.getValue().bytes().length;
            return expired;
        });
        Entry previous = cache.put(key, new Entry(bytes, System.nanoTime()));
        cacheBytes += bytes.length - (previous == null ? 0 : previous.bytes().length);
        while (cacheBytes > MAX_BYTES || cache.size() > MAX_ENTRIES) {
            cacheBytes -= cache.pollFirstEntry().getValue().bytes().length;
        }
    }

    private record Key(int z, int x, int y, SegmentTileFilter filter, long version) {}
    private record Entry(byte[] bytes, long createdAt) {}
}
