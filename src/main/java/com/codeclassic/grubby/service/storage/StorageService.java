package com.codeclassic.grubby.service.storage;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class StorageService {

    @Value("${storage.local.root:./storage}")
    private String storageRoot;

    public Path getRoot() {
        return Paths.get(storageRoot).toAbsolutePath().normalize();
    }

    public void init() throws IOException {
        Files.createDirectories(getRoot());
    }

    public String save(byte[] data, String key) throws IOException {
        Path root = getRoot();
        Path target = root.resolve(FilenameUtils.separatorsToSystem(key)).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Invalid storage key path traversal detected");
        }
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        return key;
    }

    public Resource load(String key) {
        Path file = getRoot().resolve(FilenameUtils.separatorsToSystem(key)).normalize();
        return new FileSystemResource(file);
    }

    public long size(String key) throws IOException {
        Path file = getRoot().resolve(FilenameUtils.separatorsToSystem(key)).normalize();
        if (!Files.exists(file)) return 0L;
        return Files.size(file);
    }

    public String checksumSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    public String buildBrdKey(long requestId, String extension) {
        LocalDate now = LocalDate.now();
        String ym = now.format(DateTimeFormatter.ofPattern("yyyy/MM"));
        return "brd/" + ym + "/" + requestId + "/brd-" + requestId + "." + extension;
    }
}
