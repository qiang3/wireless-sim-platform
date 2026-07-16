package com.chenmingqiang.wirelesssim.system.database;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DatabaseMigrationIT {

    private static final List<String> CORE_TABLES = List.of(
            "app_user",
            "simulation_scenario",
            "experiment_task",
            "task_execution",
            "simulation_result"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatesAllCoreTables() {
        List<String> actualTables = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN (?, ?, ?, ?, ?)
                """, String.class, CORE_TABLES.toArray());

        assertThat(actualTables).containsExactlyInAnyOrderElementsOf(CORE_TABLES);
    }
}
