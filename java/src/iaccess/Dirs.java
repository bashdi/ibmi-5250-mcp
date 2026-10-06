package iaccess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;

/** Per-user state directory (agent port/token file and agent jar copies), portable across Windows, macOS and Linux. */
final class Dirs {
    private Dirs() { }

    static Path infoDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String base = System.getenv("LOCALAPPDATA");
            return Paths.get(base != null ? base : home, "iaccess-mcp");
        }
        if (os.contains("mac")) return Paths.get(home, "Library", "Application Support", "iaccess-mcp");
        String xdg = System.getenv("XDG_STATE_HOME");
        return xdg != null && !xdg.isEmpty() ? Paths.get(xdg, "iaccess-mcp") : Paths.get(home, ".local", "state", "iaccess-mcp");
    }

    /** Creates the directory; on POSIX systems it is private to the current user (the token file lives there). */
    static Path ensure() throws IOException {
        Path d = infoDir();
        if (!Files.exists(d)) {
            Files.createDirectories(d);
            try { Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("rwx------")); }
            catch (UnsupportedOperationException ignored) { /* Windows: profile ACLs already restrict it */ }
        }
        return d;
    }
}
