package org.universaltranslator.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/** Best-effort owner-only permissions for local configuration files that may contain API credentials. */
public final class LocalConfigSecurity {
    private LocalConfigSecurity() {
    }

    /**
     * Restricts a configuration file that may hold credentials to owner-only access.
     *
     * <p>Returns whether the restriction is known to be in force. A caller that ignores the value
     * behaves exactly as before, but the outcome is no longer invisible: the permissions are read
     * back (a mount point can accept {@code setPosixFilePermissions} and then serve the old mode)
     * and a failure is recorded through {@link DebugLog}.
     *
     * <p>{@code false} is a normal answer on Windows and on non-POSIX mounts: {@code %APPDATA%}
     * already carries a per-user ACL, so "not supported here" is not an error. Those two cases are
     * kept apart in the log so a maintainer can tell them from a genuine denial.
     *
     * @param file configuration file, ignored when {@code null}
     * @return true only when owner-read/owner-write is verified after writing
     */
    public static boolean restrictToOwner(Path file) {
        if (file == null) {
            return false;
        }
        Set<PosixFilePermission> permissions = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);
        try {
            Files.setPosixFilePermissions(file, permissions);
            if (permissions.equals(Files.getPosixFilePermissions(file))) {
                return true;
            }
            // The call succeeded but the file does not report the new mode: a mount point that
            // silently ignores chmod. Keep it quiet in the log level, loud in the record.
            DebugLog.global().log("config-permissions", "file", fileName(file),
                    "result", "not-applied");
            return false;
        } catch (IOException denied) {
            DebugLog.global().log("config-permissions", "file", fileName(file),
                    "result", "failed", "type", denied.getClass().getSimpleName());
            return false;
        } catch (UnsupportedOperationException notPosix) {
            DebugLog.global().log("config-permissions", "file", fileName(file),
                    "result", "unsupported");
            return false;
        } catch (SecurityException denied) {
            DebugLog.global().log("config-permissions", "file", fileName(file),
                    "result", "refused", "type", "SecurityException");
            return false;
        }
    }

    /** File name only: the log is meant to be shareable, and the directory can identify a user. */
    private static String fileName(Path file) {
        Path name = file.getFileName();
        return name == null ? "unknown" : name.toString();
    }
}
