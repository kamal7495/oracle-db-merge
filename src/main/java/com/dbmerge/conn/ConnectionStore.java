package com.dbmerge.conn;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Saves connection details to {@code <dir>/connections.json}. A password is saved only when asked, encrypted
 * with AES-256-GCM. The key comes from {@code DBMERGE_MASTER_KEY} (base64, 32 bytes) when set, otherwise from
 * {@code <dir>/master.key}, created on first use. On POSIX systems the directory is 700 and the files 600, and a
 * key file readable by group or others is refused (as ssh does).
 *
 * <p>The ciphertext is bound to the nickname, user, host, port and service (GCM associated data): editing the
 * host in the file to point a saved password at another server makes decryption fail instead of sending the
 * password there.
 *
 * <p>Threat model: this keeps passwords out of plain text, backups and accidental copies of the JSON file. With
 * the key file next to it, anyone who can read your home directory can still decrypt; keep the key in
 * {@code DBMERGE_MASTER_KEY} (or don't save passwords) when that matters.
 */
public final class ConnectionStore {

    public static final String KEY_ENV = "DBMERGE_MASTER_KEY";
    private static final int VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Set<PosixFilePermission> OWNER_DIR = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");

    /** One saved connection. {@code password} is null when the password was not saved. */
    public record Saved(String nickname, String host, int port, String serviceName, String username, String schema,
                        Encrypted password) { }

    /** AES-GCM ciphertext (tag included) and its IV, both base64. */
    public record Encrypted(String iv, String data) { }

    record FileFormat(int version, List<Saved> connections) { }

    private final Path dir;
    private final Path file;
    private final String envKey;
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper json = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private byte[] key; // loaded lazily: only needed when a password is saved or read

    /** @param envKey value of {@value #KEY_ENV}, or null/blank to use the key file */
    public ConnectionStore(Path dir, String envKey) {
        this.dir = dir.toAbsolutePath();
        this.file = this.dir.resolve("connections.json");
        this.envKey = envKey == null || envKey.isBlank() ? null : envKey.trim();
    }

    public Path file() { return file; }

    public synchronized List<Saved> load() throws IOException {
        if (!Files.exists(file)) return List.of();
        FileFormat f = json.readValue(file.toFile(), FileFormat.class);
        if (f.version() != VERSION) throw new IOException("Unsupported " + file.getFileName() + " version " + f.version());
        return f.connections() == null ? List.of() : List.copyOf(f.connections());
    }

    public synchronized void save(List<Saved> connections) throws IOException {
        ensureDir();
        Path tmp = Files.createTempFile(dir, "connections", ".tmp");
        try {
            restrict(tmp, OWNER_FILE);
            json.writeValue(tmp.toFile(), new FileFormat(VERSION, connections));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public Encrypted encrypt(char[] password, Saved target) throws IOException {
        byte[] plain = utf8(password);
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad(target));
            Base64.Encoder b64 = Base64.getEncoder();
            return new Encrypted(b64.encodeToString(iv), b64.encodeToString(c.doFinal(plain)));
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not encrypt the password: " + e.getClass().getSimpleName(), e);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** @throws IOException when the key is wrong or missing, or the entry was edited */
    public char[] decrypt(Saved s) throws IOException {
        byte[] plain = null;
        try {
            Base64.Decoder b64 = Base64.getDecoder();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"),
                    new GCMParameterSpec(TAG_BITS, b64.decode(s.password().iv())));
            c.updateAAD(aad(s));
            plain = c.doFinal(b64.decode(s.password().data()));
            CharBuffer cb = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plain));
            char[] out = new char[cb.remaining()];
            cb.get(out);
            Arrays.fill(cb.array(), '\0');
            return out;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("the saved password could not be decrypted (different key, or the entry was edited)");
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    private static byte[] aad(Saved s) {
        return ("dbmerge-v1|" + s.nickname() + "|" + s.username() + "|" + s.host() + "|" + s.port() + "|" + s.serviceName())
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] utf8(char[] chars) {
        ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] out = new byte[bb.remaining()];
        bb.get(out);
        Arrays.fill(bb.array(), (byte) 0);
        return out;
    }

    private synchronized byte[] key() throws IOException {
        if (key != null) return key;
        if (envKey != null) {
            byte[] k;
            try {
                k = Base64.getDecoder().decode(envKey);
            } catch (IllegalArgumentException e) {
                throw new IOException(KEY_ENV + " is not valid base64");
            }
            if (k.length != 32) throw new IOException(KEY_ENV + " must decode to 32 bytes (got " + k.length + ")");
            return key = k;
        }
        Path keyFile = dir.resolve("master.key");
        if (!Files.exists(keyFile)) {
            ensureDir();
            byte[] k = new byte[32];
            random.nextBytes(k);
            Path tmp = Files.createTempFile(dir, "master", ".tmp");
            try {
                restrict(tmp, OWNER_FILE);
                Files.writeString(tmp, Base64.getEncoder().encodeToString(k));
                Files.move(tmp, keyFile); // fails if another process created it meanwhile; we then read theirs
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // fall through and read the existing key
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        if (posix()) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(keyFile);
            if (perms.stream().anyMatch(p -> !OWNER_FILE.contains(p))) {
                throw new IOException(keyFile + " is readable by other users; run: chmod 600 " + keyFile);
            }
        }
        byte[] k = Base64.getDecoder().decode(Files.readString(keyFile).trim());
        if (k.length != 32) throw new IOException(keyFile + " does not hold a 32-byte key");
        return key = k;
    }

    private void ensureDir() throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            restrict(dir, OWNER_DIR);
        }
    }

    private static void restrict(Path p, Set<PosixFilePermission> perms) throws IOException {
        if (posix()) Files.setPosixFilePermissions(p, perms);
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }
}
