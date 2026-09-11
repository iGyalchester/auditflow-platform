package com.auditflow.common.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reason this repo uses Flyway rather than running a script on startup.
 *
 * <p>Gateway, alerting and enrichment all migrate the same database on boot.
 * Under {@code spring.sql.init} they raced: `docker compose up` starts them
 * together and a rolling deploy overlaps old and new tasks, so two services
 * could execute `CREATE TABLE IF NOT EXISTS` at the same moment and one
 * would fail on a duplicate relation that the IF NOT EXISTS was supposed to
 * prevent. (It is checked and then created, not atomically.)
 *
 * <p>Flyway takes a Postgres advisory lock around the whole migration, so
 * one instance applies and the rest wait and find nothing to do. Four
 * threads against an empty database is that scenario, compressed.
 */
@Testcontainers(disabledWithoutDocker = true)
class SchemaMigrationConcurrencyTest {

    private static final int CONCURRENT_SERVICES = 4;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("auditflow").withUsername("auditflow").withPassword("auditflow");

    private static DataSource dataSource() {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    @Test
    void fourServicesMigratingAtOnceAllSucceedAndApplyEachVersionOnce() throws Exception {
        List<Callable<Integer>> migrations = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_SERVICES; i++) {
            migrations.add(() -> Flyway.configure()
                    .dataSource(dataSource())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()
                    .migrationsExecuted);
        }

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_SERVICES);
        int applied = 0;
        try {
            for (Future<Integer> result : pool.invokeAll(migrations)) {
                // get() rethrows whatever the thread threw: a race shows up
                // here as an ExecutionException, not as a quiet miscount
                applied += result.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(applied)
                .as("exactly one instance applies each version; the rest wait and find nothing to do")
                .isEqualTo(versionsOnDisk());

        assertThat(appliedVersions())
                .as("no version applied twice")
                .doesNotHaveDuplicates()
                .hasSize(versionsOnDisk());
    }

    private static int versionsOnDisk() {
        return Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .load()
                .info()
                .all().length;
    }

    private static List<String> appliedVersions() throws Exception {
        List<String> versions = new ArrayList<>();
        try (Connection connection = dataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        }
        return versions;
    }
}
