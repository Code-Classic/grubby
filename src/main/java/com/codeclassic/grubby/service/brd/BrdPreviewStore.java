package com.codeclassic.grubby.service.brd;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory store for BRD preview markdown by request id.
 * In a real implementation this could be Redis or database.
 */
@Service
public class BrdPreviewStore {

    private final Map<Long, String> previews = new ConcurrentHashMap<>();

    public void put(Long requestId, String markdown) {
        if (requestId != null && markdown != null) {
            previews.put(requestId, markdown);
        }
    }

    public Optional<String> get(Long requestId) {
        return Optional.ofNullable(previews.get(requestId));
    }

    public void clear(Long requestId) {
        previews.remove(requestId);
    }
}
