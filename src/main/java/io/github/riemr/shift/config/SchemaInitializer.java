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

            // Create interval tables if missing (transition from quarter tables)
            jdbc.execute("CREATE TABLE IF NOT EXISTS register_demand_interval (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "store_code VARCHAR(10) NOT NULL REFERENCES store(store_code), " +
                    "target_date DATE NOT NULL, " +
                    "from_time TIME NOT NULL, " +
                    "to_time TIME NOT NULL, " +
                    "demand INTEGER NOT NULL, " +
                    "task_code VARCHAR(32), " +
                    "created_at TIMESTAMPTZ DEFAULT now(), " +
                    "updated_at TIMESTAMPTZ DEFAULT now(), " +
                    "CONSTRAINT chk_register_from_lt_to CHECK (to_time > from_time)" +
                    ")");
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_register_demand_interval ON register_demand_interval (store_code, target_date, from_time, to_time, COALESCE(task_code, ''))");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_register_demand_interval_date ON register_demand_interval (store_code, target_date)");

            jdbc.execute("CREATE TABLE IF NOT EXISTS work_demand_interval (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "store_code VARCHAR(10) NOT NULL REFERENCES store(store_code), " +
                    "department_code VARCHAR(10) NOT NULL REFERENCES department_master(department_code), " +
                    "target_date DATE NOT NULL, " +
                    "from_time TIME NOT NULL, " +
                    "to_time TIME NOT NULL, " +
                    "demand INTEGER NOT NULL, " +
                    "task_code VARCHAR(32), " +
                    "created_at TIMESTAMPTZ DEFAULT now(), " +
                    "updated_at TIMESTAMPTZ DEFAULT now(), " +
                    "CONSTRAINT chk_work_from_lt_to CHECK (to_time > from_time)" +
                    ")");
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_work_demand_interval ON work_demand_interval (store_code, department_code, target_date, from_time, to_time, COALESCE(task_code, ''))");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_work_demand_interval_date ON work_demand_interval (store_code, department_code, target_date)");

            log.info("Schema checked/initialized: is_register column and employee_task_skill table ensured.");
        } catch (Exception e) {
            log.warn("Schema initialization failed: {}", e.getMessage());
        }
    }
}
