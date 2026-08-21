package io.github.jtplatform.media.ftp;

import io.github.jtplatform.common.port.RecordingUploadCredentialPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ftpserver.ftplet.Authentication;
import org.apache.ftpserver.ftplet.AuthenticationFailedException;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.User;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission;
import org.apache.ftpserver.usermanager.impl.WritePermission;

/** In-memory, task-scoped FTP credentials. Plaintext passwords are returned once and never retained. */
public final class TemporaryFtpCredentialService implements RecordingUploadCredentialPort, UserManager {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RecordingFtpProperties properties;
    private final Clock clock;
    private final String serverAddress;
    private final Map<String, Lease> byTask = new ConcurrentHashMap<>();
    private final Map<String, Lease> byUsername = new ConcurrentHashMap<>();
    private final Map<CommandKey, String> taskByCommand = new ConcurrentHashMap<>();

    public TemporaryFtpCredentialService(RecordingFtpProperties properties, Clock clock, String serverAddress) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.serverAddress = required(serverAddress, "serverAddress");
        properties.validate();
    }

    @Override
    public synchronized RecordingUploadCredentials issue(String taskId, String deviceId) {
        purgeExpired();
        String normalizedTask = required(taskId, "taskId");
        String normalizedDevice = required(deviceId, "deviceId");
        if (byTask.containsKey(normalizedTask)) {
            throw new IllegalStateException("FTP credentials already exist for task " + normalizedTask);
        }
        String username = "ru_" + token(12);
        String password = token(24);
        byte[] salt = randomBytes(16);
        Instant expiresAt = clock.instant().plus(properties.getCredentialTtl());
        Path home = properties.getRoot().toAbsolutePath().normalize().resolve(safeTaskPath(normalizedTask));
        try {
            Files.createDirectories(home);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Unable to create FTP task directory", failure);
        }
        Lease lease = new Lease(normalizedTask, normalizedDevice, username, salt,
                hash(salt, password), expiresAt, home);
        byTask.put(normalizedTask, lease);
        byUsername.put(username, lease);
        return new RecordingUploadCredentials(normalizedTask, serverAddress, properties.getPort(),
                username, password, "/", expiresAt);
    }

    @Override
    public synchronized void bindCommand(String taskId, String deviceId, int commandSerialNo) {
        Lease lease = activeByTask(required(taskId, "taskId"));
        if (!lease.deviceId().equals(required(deviceId, "deviceId"))) {
            throw new IllegalArgumentException("task does not belong to device");
        }
        if (commandSerialNo < 0 || commandSerialNo > 0xffff) {
            throw new IllegalArgumentException("commandSerialNo must be in range 0..65535");
        }
        taskByCommand.put(new CommandKey(lease.deviceId(), commandSerialNo), lease.taskId());
    }

    @Override
    public synchronized void completeCommand(String deviceId, int commandSerialNo) {
        String taskId = taskByCommand.remove(new CommandKey(required(deviceId, "deviceId"), commandSerialNo));
        if (taskId != null) revokeTask(taskId);
    }

    @Override
    public synchronized void revokeTask(String taskId) {
        Lease lease = byTask.remove(required(taskId, "taskId"));
        if (lease == null) return;
        byUsername.remove(lease.username(), lease);
        taskByCommand.entrySet().removeIf(entry -> entry.getValue().equals(lease.taskId()));
    }

    public synchronized int purgeExpired() {
        Instant now = clock.instant();
        List<String> expired = byTask.values().stream()
                .filter(lease -> !lease.expiresAt().isAfter(now))
                .map(Lease::taskId).toList();
        expired.forEach(this::revokeTask);
        return expired.size();
    }

    public boolean isActive(String username) {
        return activeByUsername(username) != null;
    }

    public String taskIdForUsername(String username) {
        Lease lease = activeByUsername(username);
        return lease == null ? null : lease.taskId();
    }

    public String deviceIdForUsername(String username) {
        Lease lease = activeByUsername(username);
        return lease == null ? null : lease.deviceId();
    }

    public Path homeForUsername(String username) {
        Lease lease = activeByUsername(username);
        return lease == null ? null : lease.home();
    }

    @Override
    public User authenticate(Authentication authentication) throws AuthenticationFailedException {
        if (!(authentication instanceof UsernamePasswordAuthentication credentials)) {
            throw new AuthenticationFailedException("Anonymous FTP login is disabled");
        }
        Lease lease = activeByUsername(credentials.getUsername());
        if (lease == null || !MessageDigest.isEqual(lease.passwordHash(), hash(lease.salt(), credentials.getPassword()))) {
            throw new AuthenticationFailedException("Invalid or expired temporary credential");
        }
        return user(lease);
    }

    @Override
    public User getUserByName(String username) {
        Lease lease = activeByUsername(username);
        return lease == null ? null : user(lease);
    }

    @Override public String[] getAllUserNames() { purgeExpired(); return byUsername.keySet().toArray(String[]::new); }
    @Override public void delete(String username) { Lease lease = byUsername.get(username); if (lease != null) revokeTask(lease.taskId()); }
    @Override public void save(User user) throws FtpException { throw new FtpException("Temporary users can only be issued by the credential service"); }
    @Override public boolean doesExist(String username) { return activeByUsername(username) != null; }
    @Override public String getAdminName() { return ""; }
    @Override public boolean isAdmin(String username) { return false; }

    private Lease activeByTask(String taskId) {
        Lease lease = byTask.get(taskId);
        if (lease == null || !lease.expiresAt().isAfter(clock.instant())) {
            if (lease != null) revokeTask(taskId);
            throw new IllegalStateException("FTP credential is missing or expired for task " + taskId);
        }
        return lease;
    }

    private Lease activeByUsername(String username) {
        if (username == null) return null;
        Lease lease = byUsername.get(username);
        if (lease != null && !lease.expiresAt().isAfter(clock.instant())) {
            revokeTask(lease.taskId());
            return null;
        }
        return lease;
    }

    private static BaseUser user(Lease lease) {
        BaseUser user = new BaseUser();
        user.setName(lease.username());
        user.setPassword("");
        user.setEnabled(true);
        user.setHomeDirectory(lease.home().toString());
        user.setMaxIdleTime(300);
        user.setAuthorities(List.of(new WritePermission(), new ConcurrentLoginPermission(1, 1)));
        return user;
    }

    private static String safeTaskPath(String taskId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(taskId.getBytes(StandardCharsets.UTF_8));
    }

    private static String token(int bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(bytes));
    }

    private static byte[] randomBytes(int count) { byte[] value = new byte[count]; RANDOM.nextBytes(value); return value; }

    private static byte[] hash(byte[] salt, String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(password == null ? new byte[0] : password.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String required(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    private record Lease(String taskId, String deviceId, String username, byte[] salt,
                         byte[] passwordHash, Instant expiresAt, Path home) { }
    private record CommandKey(String deviceId, int serialNo) { }
}
