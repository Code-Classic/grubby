package com.codeclassic.grubby.service.brd;

import com.codeclassic.grubby.service.cache.InMemoryCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * P4 — BRD preview markdown store backed by InMemoryCacheService (TTL-evicted).
 *
 * The original implementation used an unbounded ConcurrentHashMap with no eviction policy.
 * Each completed BRD held 10–100 KB of markdown in the JVM heap indefinitely, causing a
 * slow memory leak that would eventually OOM the process on long-running instances.
 *
 * This wrapper delegates to InMemoryCacheService which:
 *   - Evicts entries after the configured TTL (cache.brd.ttlMillis, default 12 h)
 *   - Runs a background cleanup every cache.cleanup.intervalMillis (default 10 min)
 *   - Uses a ConcurrentHashMap internally (still thread-safe, no lock contention)
 *
 * The 12-hour TTL is generous: if a user hasn't viewed the preview within 12 hours of
 * generation the preview endpoint falls back to loading the markdown from the stored .md
 * document in StorageService, so no data is lost.
 */
@Service
@RequiredArgsConstructor
public class BrdPreviewStore {

    private static final String PREFIX = "preview:";

    private final InMemoryCacheService cacheService;

    public void put(Long requestId, String markdown) {
        if (requestId == null || markdown == null) return;
        cacheService.putBrd(PREFIX + requestId, markdown);
    }

    public Optional<String> get(Long requestId) {
        if (requestId == null) return Optional.empty();
        return cacheService.getBrd(PREFIX + requestId);
    }

    public void clear(Long requestId) {
        if (requestId == null) return;
        cacheService.removeBrd(PREFIX + requestId);
    }
}
