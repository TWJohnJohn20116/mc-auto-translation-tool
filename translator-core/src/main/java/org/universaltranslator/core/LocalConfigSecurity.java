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

    public static void restrictToOwner(Path file) {
        Set<PosixFilePermission> permissions = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);
        try {
            Files.setPosixFilePermissions(file, permissions);
        } catch (IOException ignored) {
            // Some launchers and filesystems do not expose POSIX permissions.
        } catch (UnsupportedOperationException ignored) {
            // Windows and non-POSIX filesystems use their existing ACLs.
        } catch (SecurityException ignored) {
            // Translation remains usable if the launcher denies permission changes.
        }
    }
}
