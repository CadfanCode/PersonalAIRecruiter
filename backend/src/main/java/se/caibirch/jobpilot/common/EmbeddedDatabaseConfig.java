package se.caibirch.jobpilot.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The installed app has no database to set up: PostgreSQL 16 runs inside it (ADR 0012). Data lives in
 * {@code <data-dir>/db} and survives restarts; the port is picked at random on each start and never leaves loopback.
 */
@Configuration
@ConditionalOnProperty(name = "jobpilot.db.embedded", havingValue = "true", matchIfMissing = true)
class EmbeddedDatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedDatabaseConfig.class);

    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres(@Value("${jobpilot.data-dir}") Path dataDir) throws IOException {
        Path dir = dataDir.resolve("db");
        Files.createDirectories(dir);
        stopStalePostmaster(dir);
        log.info("Starting the embedded database in {}", dir);
        return EmbeddedPostgres.builder().setDataDirectory(dir).setCleanDataDirectory(false)
                .setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    /**
     * After a crash or a force-quit the database from the previous run can still be running as its own process and would
     * refuse the next start ("lock file postmaster.pid already exists"). It belongs to this data directory and its client is gone,
     * so it is stopped first. A process that merely has the same pid, but is not a postgres running in this directory, is left alone.
     */
    static void stopStalePostmaster(Path dir) {
        Path pidFile = dir.resolve("postmaster.pid");
        if (!Files.isRegularFile(pidFile)) {
            return;
        }
        try {
            long pid = Long.parseLong(Files.readAllLines(pidFile).get(0).trim());
            ProcessHandle handle = ProcessHandle.of(pid).filter(ProcessHandle::isAlive).orElse(null);
            if (handle == null) {
                return;
            }
            String command = handle.info().commandLine().orElse("");
            if (!command.contains("postgres") || !command.contains(dir.toString())) {
                return;
            }
            log.warn("The database of an earlier run is still running (pid {}); stopping it before starting", pid);
            handle.destroy();
            try {
                handle.onExit().get(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                handle.destroyForcibly();
                handle.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (IOException | RuntimeException | InterruptedException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Could not check for a database left over from an earlier run: {}", e.toString());
        }
    }

    @Bean(destroyMethod = "close")
    @Primary
    DataSource dataSource(EmbeddedPostgres postgres) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl("postgres", "postgres"));
        config.setUsername("postgres");
        config.setMaximumPoolSize(10);
        return new HikariDataSource(config);
    }
}
