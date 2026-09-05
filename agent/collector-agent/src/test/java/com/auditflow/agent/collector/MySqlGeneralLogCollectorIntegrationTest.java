package com.auditflow.agent.collector;

import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The money test: a real MySQL with its general log enabled, a query
 * containing PII, and the collector proving the statement comes back as
 * an event with every literal redacted. Uses the root account because
 * enabling the log needs SUPER; production runs a read-only agent user.
 */
@Testcontainers(disabledWithoutDocker = true)
class MySqlGeneralLogCollectorIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9")
            .withDatabaseName("resistance")
            .withUsername("test")
            .withPassword("test")
            // our own config override: Testcontainers' built-in one still sets
            // innodb_log_file_size, which MySQL 9 removed (container dies at
            // init) - and ours turns the general log on at startup
            .withConfigurationOverride("mysql-conf");

    static JdbcTemplate rootJdbc;

    @BeforeAll
    static void connectAsRoot() {
        // root can read mysql.general_log; the app user cannot
        DriverManagerDataSource ds = new DriverManagerDataSource(
                MYSQL.getJdbcUrl().replace("/resistance", "/mysql"), "root", "test");
        rootJdbc = new JdbcTemplate(ds);

        // MySQL logs thousands of statements while loading its time zone
        // tables at startup. The collector now drains a backlog properly
        // instead of skipping to the newest row, so that history would take
        // minutes to walk at a small batch size - and it is not what any of
        // these tests are about. Start from an empty log.
        rootJdbc.execute("TRUNCATE TABLE mysql.general_log");
    }

    @Test
    void collectsAndRedactsAStatementFromTheGeneralLog() {
        MySqlGeneralLogCollector collector =
                new MySqlGeneralLogCollector(rootJdbc, "resistance", "resistance-mysql", 500);
        // establish the checkpoint before the marker statement exists
        collector.collect();
        collector.commit();

        rootJdbc.execute("CREATE TABLE IF NOT EXISTS resistance.user_account (email VARCHAR(255))");
        rootJdbc.execute(
                "SELECT * FROM resistance.user_account WHERE email = 'boris.secret@example.com'");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<AuditEvent> events = collector.collect();
            assertThat(events)
                    .anySatisfy(event -> {
                        assertThat(event.getQuery()).contains("user_account");
                        assertThat(event.getQuery()).doesNotContain("boris.secret@example.com");
                        assertThat(event.getQuery()).contains("email = ?");
                        assertThat(event.getCustomerId()).isEqualTo("resistance");
                        assertThat(event.getAction()).isEqualTo("SELECT");
                    });
        });
    }

    @Test
    void checkpointHoldsUntilCommitted() {
        MySqlGeneralLogCollector collector =
                new MySqlGeneralLogCollector(rootJdbc, "resistance", "resistance-mysql", 500);
        collector.collect();
        collector.commit();

        rootJdbc.execute("SELECT 'checkpoint-probe-value' FROM dual");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<AuditEvent> first = collector.collect();
            assertThat(first).isNotEmpty();
        });

        // not committed -> the same events are re-read (at-least-once), with
        // identical deterministic ids so downstream dedupe collapses them
        List<AuditEvent> again = collector.collect();
        assertThat(again).isNotEmpty();
        assertThat(again.get(0).getEventId())
                .isEqualTo(collector.collect().get(0).getEventId());

        collector.commit();
        assertThat(collector.collect()).isEmpty();
    }

    @Test
    void everyStatementInABacklogLargerThanOneBatchIsCollectedExactlyOnce() {
        // The bug this proves gone: the checkpoint used to come from
        // SELECT MAX(event_time) over the whole table, so a backlog bigger
        // than one batch moved the cursor past rows the batch never
        // returned. They were never read again - silent, permanent loss.
        MySqlGeneralLogCollector collector =
                new MySqlGeneralLogCollector(rootJdbc, "resistance", "resistance-mysql", 5);
        collector.collect();
        collector.commit();

        int probes = 23;
        for (int i = 0; i < probes; i++) {
            rootJdbc.execute("SELECT /* gap_probe_%02d */ 1".formatted(i));
        }

        // A plain drain loop rather than awaitility: the loop has state
        // (it commits, and remembers what it has seen), and retrying a
        // stateful lambda would make a failure hard to read.
        Set<String> seenIds = new HashSet<>();
        List<String> queries = new ArrayList<>();
        for (int poll = 0; poll < 40; poll++) {
            for (AuditEvent event : collector.collect()) {
                // ids are deterministic, so a re-read would show up here
                assertThat(seenIds.add(event.getEventId()))
                        .as("event %s was collected twice", event.getEventId()).isTrue();
                queries.add(event.getQuery());
            }
            collector.commit();
        }

        for (int i = 0; i < probes; i++) {
            String marker = "gap_probe_%02d".formatted(i);
            assertThat(queries).as("probe %s was never collected", marker)
                    .anySatisfy(query -> assertThat(query).contains(marker));
        }
    }
}
