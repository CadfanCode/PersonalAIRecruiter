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
        log.info("Starting the embedded database in {}", dir);
        return EmbeddedPostgres.builder().setDataDirectory(dir).setCleanDataDirectory(false)
                .setServerConfig("listen_addresses", "127.0.0.1").start();
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
