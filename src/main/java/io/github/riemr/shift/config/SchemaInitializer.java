package io.github.riemr.shift.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class SchemaInitializer {
    private final JdbcTemplate jdbc;

    @PostConstruct
    public void ensureTables() {
        try {
            // Ensure department_master.is_register exists
            jdbc.execute("ALTER TABLE IF EXISTS department_master ADD COLUMN IF NOT EXISTS is_register BOOLEAN NOT NULL DEFAULT FALSE");
            jdbc.execute("UPDATE department_master SET is_register = TRUE WHERE department_code = '520'");

            // Create employee_task_skill table if missing
            jdbc.execute("CREATE TABLE IF NOT EXISTS employee_task_skill (" +
                    "employee_code VARCHAR(10) NOT NULL REFERENCES employee(employee_code)," +
                    "task_code VARCHAR(32) NOT NULL REFERENCES task_master(task_code)," +
                    "skill_level SMALLINT NOT NULL," +
                    "PRIMARY KEY (employee_code, task_code))");

            log.info("Schema checked/initialized: is_register column and employee_task_skill table ensured.");
        } catch (Exception e) {
            log.warn("Schema initialization failed: {}", e.getMessage());
        }
    }
}

