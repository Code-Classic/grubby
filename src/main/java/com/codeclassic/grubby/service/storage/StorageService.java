package com.codeclassic.grubby.service.storage;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Handles local filesystem storage for generated BRD documents.
 *
 * Improvements:
 * - Root directory is created eagerly on startup via @PostConstruct
 * - Writes are atomic: written to a temp file then moved, preventing corrupt partial files
 * - load() validates file existence before returning a Resource
 */
@Slf4j
@Service
public class StorageService {

    @Value("${storage.local.root:./storage}")
    private String storageRoot;

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(getRoot());
        log.info("Storage root initialized at: {}", getRoot());
    }

    public Path getRoot() {
        return Paths.get(storageRoot).toAbsolutePath().normalize();
    }

    /**
     * Saves bytes under the given key using an atomic write (temp → rename).
     */
    public String save(byte[] data, String key) throws IOException {
        Path root = getRoot();
        Path target = resolveKey(root, key);
        Files.createDirectories(target.getParent());

        // Write atomically to avoid partial/corrupt files visible to readers
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (UnsupportedOperationException ex) {
            // ATOMIC_MOVE not supported on all filesystems — fall back
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (Files.exists(tmp)) {
                Files.deleteIfExists(tmp);
            }
        }
        log.debug("Saved {} bytes to key '{}'", data.length, key);
        return key;
    }

    /**
     * Returns a Resource for the given key, validating the file exists.
     */
    public Resource load(String key) {
        Path file = resolveKey(getRoot(), key);
        if (!Files.exists(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Stored file not found for key: " + key);
        }
        return new FileSystemResource(file);
    }

    public long size(String key) throws IOException {
        Path file = resolveKey(getRoot(), key);
        return Files.exists(file) ? Files.size(file) : 0L;
    }

    public String checksumSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            log.warn("SHA-256 not available");
            return "";
        }
    }

    public String buildBrdKey(long requestId, String extension) {
        String ym = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        return "brd/" + ym + "/" + requestId + "/brd-" + requestId + "." + extension;
    }

    private Path resolveKey(Path root, String key) {
        Path resolved = root.resolve(FilenameUtils.separatorsToSystem(key)).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key — path traversal detected: " + key);
        }
        return resolved;
    }
}
