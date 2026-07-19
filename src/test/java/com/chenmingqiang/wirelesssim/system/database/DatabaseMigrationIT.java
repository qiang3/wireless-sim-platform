package com.chenmingqiang.wirelesssim.system.database;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DatabaseMigrationIT {

    private static final List<String> CORE_TABLES = List.of(
            "app_user",
            "simulation_scenario",
            "experiment_task",
            "task_execution",
            "simulation_result",
            "outbox_event"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatesAllCoreTables() {
        List<String> actualTables = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN (?, ?, ?, ?, ?, ?)
                """, String.class, CORE_TABLES.toArray());

        assertThat(actualTables).containsExactlyInAnyOrderElementsOf(CORE_TABLES);
    }

    /** 验证Outbox发布器后续依赖的唯一约束和扫描索引已经由V3迁移创建。 */
    @Test
    void flywayCreatesOutboxConstraintsAndIndexes() {
        List<String> indexNames = jdbcTemplate.queryForList("""
                SELECT DISTINCT index_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'outbox_event'
                """, String.class);

        assertThat(indexNames).contains(
                "PRIMARY",
                "uk_outbox_event_id",
                "uk_outbox_business_event",
                "idx_outbox_publish_candidate",
                "idx_outbox_sending_recovery"
        );
    }

    /** 验证状态、发布次数和消息版本的默认值，防止插入代码遗漏字段时产生不确定状态。 */
    @Test
    void flywayCreatesOutboxDefaults() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, column_default
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'outbox_event'
                  AND column_name IN ('status', 'publish_attempts', 'schema_version', 'priority')
                """);

        assertThat(columns)
                .anySatisfy(column -> {
                    assertThat(column.get("column_name")).isEqualTo("status");
                    assertThat(column.get("column_default")).isEqualTo("PENDING");
                })
                .anySatisfy(column -> {
                    assertThat(column.get("column_name")).isEqualTo("publish_attempts");
                    assertThat(String.valueOf(column.get("column_default"))).isEqualTo("0");
                })
                .anySatisfy(column -> {
                    assertThat(column.get("column_name")).isEqualTo("schema_version");
                    assertThat(String.valueOf(column.get("column_default"))).isEqualTo("1");
                })
                .anySatisfy(column -> {
                    assertThat(column.get("column_name")).isEqualTo("priority");
                    assertThat(String.valueOf(column.get("column_default"))).isEqualTo("3");
                });
    }
}
