package com.mcpserver.connectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * AES-256-GCM encryption for connection credentials, keyed by a file generated on first use
 * (./data/connections.key). There is no Vault/KMS integration in this app yet — this is the
 * pragmatic pre-Phase-6, single-JAR, trusted-network-only equivalent (see DECISIONS.md); Vault/KMS
 * is the documented Phase 5 upgrade path.
 */
@Component
public class CredentialCipher {

    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);
    private static final String KEY_FILE = "./data/connections.key";
    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecretKey key;

    public CredentialCipher() {
        this.key = loadOrCreateKey();
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ciphertext.length);
            buf.put(iv).put(ciphertext);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt credential", e);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            ByteBuffer buf = ByteBuffer.wrap(all);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt credential", e);
        }
    }

    private SecretKey loadOrCreateKey() {
        try {
            Path path = Path.of(KEY_FILE);
            if (Files.exists(path)) {
                byte[] raw = Base64.getDecoder().decode(Files.readString(path).strip());
                return new SecretKeySpec(raw, "AES");
            }
            Files.createDirectories(path.getParent());
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            Files.writeString(path, Base64.getEncoder().encodeToString(raw));
            restrictToOwner(path);
            log.info("Generated new connection-credential encryption key at {}", KEY_FILE);
            return new SecretKeySpec(raw, "AES");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load/create credential encryption key at " + KEY_FILE, e);
        }
    }

    private void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem (e.g. Windows) — best effort only.
            log.debug("Could not restrict permissions on {}: {}", path, e.getMessage());
        }
    }
}
