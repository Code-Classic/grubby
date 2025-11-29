package com.codeclassic.grubby.service.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory TTL cache for analysis summaries and BRD markdown.
 * Not distributed; suitable for single-instance dev setups.
 */
@Service
public class InMemoryCacheService {

    private static class Entry {
        final Object value;
        final long expiresAt;
        Entry(Object value, long expiresAt) { this.value = value; this.expiresAt = expiresAt; }
        boolean expired() { return System.currentTimeMillis() > expiresAt; }
    }

    private final Map<String, Entry> analysisCache = new ConcurrentHashMap<>();
    private final Map<String, Entry> brdCache = new ConcurrentHashMap<>();

    @Value("${cache.analysis.ttlMillis:172800000}") // 48h
    private long analysisTtlMillis;

    @Value("${cache.brd.ttlMillis:43200000}") // 12h
    private long brdTtlMillis;

    public <T> Optional<T> getAnalysis(String key, Class<T> type) {
        return get(analysisCache, key, type);
    }

    public void putAnalysis(String key, Object value) {
        put(analysisCache, key, value, analysisTtlMillis);
    }

    public Optional<String> getBrd(String key) {
        return get(brdCache, key, String.class);
    }

    public void putBrd(String key, String markdown) {
        put(brdCache, key, markdown, brdTtlMillis);
    }

    private <T> Optional<T> get(Map<String, Entry> map, String key, Class<T> type) {
        if (key == null) return Optional.empty();
        Entry e = map.get(key);
        if (e == null) return Optional.empty();
        if (e.expired()) {
            map.remove(key);
            return Optional.empty();
        }
        Object v = e.value;
        if (type.isInstance(v)) return Optional.of(type.cast(v));
        return Optional.empty();
    }

    private void put(Map<String, Entry> map, String key, Object value, long ttlMillis) {
        if (key == null || value == null) return;
        long exp = System.currentTimeMillis() + Math.max(1000L, ttlMillis);
        map.put(key, new Entry(value, exp));
    }

    @Scheduled(fixedDelayString = "${cache.cleanup.intervalMillis:600000}") // 10 min
    public void cleanup() {
        purge(analysisCache);
        purge(brdCache);
    }

    private void purge(Map<String, Entry> map) {
        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> e.getValue().expiresAt < now);
    }
}
