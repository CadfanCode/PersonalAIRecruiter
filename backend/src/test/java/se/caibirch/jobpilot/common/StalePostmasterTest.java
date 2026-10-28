package se.caibirch.jobpilot.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The embedded database left running by a crashed app must not block the next start; nothing else is ever touched. */
class StalePostmasterTest {

    @TempDir
    Path tmp;

    /** A harmless stand-in that looks like a postgres of this data directory: a script called postgres, started with -D <dir>. */
    Process fakePostgres(Path dataDir) throws IOException {
        Path script = tmp.resolve("bin").resolve("postgres");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "#!/bin/sh\nsleep 600\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return new ProcessBuilder(script.toString(), "-D", dataDir.toString()).start();
    }

    @Test
    void aDatabaseOfThisDirectoryThatIsStillRunningIsStopped() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"));
        Path dir = Files.createDirectories(tmp.resolve("db"));
        Process stale = fakePostgres(dir);
        Thread.sleep(300);
        Files.writeString(dir.resolve("postmaster.pid"), stale.pid() + "\n" + dir + "\n");

        EmbeddedDatabaseConfig.stopStalePostmaster(dir);

        assertThat(stale.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).as("the stale database was stopped").isTrue();
    }

    @Test
    void aProcessThatOnlyHasTheSamePidIsLeftAlone() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"));
        Path dir = Files.createDirectories(tmp.resolve("db"));
        Process other = new ProcessBuilder("sleep", "600").start();
        try {
            Files.writeString(dir.resolve("postmaster.pid"), other.pid() + "\n" + dir + "\n");

            EmbeddedDatabaseConfig.stopStalePostmaster(dir);

            assertThat(other.isAlive()).as("not a postgres of this directory").isTrue();
        } finally {
            other.destroyForcibly();
        }
    }

    @Test
    void aPostgresOfAnotherDirectoryIsLeftAlone() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"));
        Path dir = Files.createDirectories(tmp.resolve("db"));
        Path elsewhere = Files.createDirectories(tmp.resolve("other-db"));
        Process foreign = fakePostgres(elsewhere);
        try {
            Thread.sleep(300);
            Files.writeString(dir.resolve("postmaster.pid"), foreign.pid() + "\n" + dir + "\n");

            EmbeddedDatabaseConfig.stopStalePostmaster(dir);

            assertThat(foreign.isAlive()).isTrue();
        } finally {
            foreign.destroyForcibly();
        }
    }

    @Test
    void nothingHappensWithoutALeftOverFileOrWithAGarbageOne() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("db"));
        EmbeddedDatabaseConfig.stopStalePostmaster(dir);
        Files.writeString(dir.resolve("postmaster.pid"), "not a number\n");
        EmbeddedDatabaseConfig.stopStalePostmaster(dir);
        Files.writeString(dir.resolve("postmaster.pid"), "999999999\n" + dir + "\n"); // no such process
        EmbeddedDatabaseConfig.stopStalePostmaster(dir);
        assertThat(Files.exists(dir.resolve("postmaster.pid"))).isTrue();
    }
}
