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
            // Department tables (ensure exist for batch imports)
            jdbc.execute("CREATE TABLE IF NOT EXISTS department_master (" +
                    "department_code VARCHAR(32) PRIMARY KEY, " +
                    "department_name TEXT NOT NULL, " +
                    "display_order INTEGER, " +
                    "is_active BOOLEAN NOT NULL DEFAULT TRUE, " +
                    "is_register BOOLEAN NOT NULL DEFAULT FALSE" +
                    ")");
            jdbc.execute("CREATE TABLE IF NOT EXISTS store_department (" +
                    "store_code VARCHAR(10) NOT NULL REFERENCES store(store_code), " +
                    "department_code VARCHAR(32) NOT NULL REFERENCES department_master(department_code), " +
                    "display_order INTEGER, " +
                    "is_active BOOLEAN NOT NULL DEFAULT TRUE, " +
                    "PRIMARY KEY (store_code, department_code)" +
                    ")");
            jdbc.execute("CREATE TABLE IF NOT EXISTS employee_department (" +
                    "employee_code VARCHAR(10) NOT NULL REFERENCES employee(employee_code), " +
                    "department_code VARCHAR(32) NOT NULL REFERENCES department_master(department_code), " +
                    "PRIMARY KEY (employee_code, department_code)" +
                    ")");
            jdbc.execute("CREATE TABLE IF NOT EXISTS employee_department_skill (" +
                    "employee_code VARCHAR(10) NOT NULL REFERENCES employee(employee_code), " +
                    "department_code VARCHAR(32) NOT NULL REFERENCES department_master(department_code), " +
                    "skill_level SMALLINT NOT NULL, " +
                    "PRIMARY KEY (employee_code, department_code)" +
                    ")");
            // Ensure department_master has register department '520' (legacy)
            jdbc.execute("INSERT INTO department_master(department_code, department_name, is_register) VALUES ('520','Register', TRUE) ON CONFLICT (department_code) DO NOTHING");

            // task_master: add department_code for task categorization
            jdbc.execute("ALTER TABLE IF EXISTS task_master ADD COLUMN IF NOT EXISTS department_code VARCHAR(32)");
            // backfill nulls prior to switching PK
            jdbc.execute("UPDATE task_master SET department_code = '520' WHERE department_code IS NULL");
            // Switch primary key to (task_code, department_code)
            try {
                jdbc.execute("ALTER TABLE task_master DROP CONSTRAINT IF EXISTS task_master_pkey");
            } catch (Exception ignore) {}
            jdbc.execute("ALTER TABLE task_master ADD CONSTRAINT task_master_pkey PRIMARY KEY (task_code, department_code)");
            // Optional FK: keep nullable for backward compatibility (uncomment if needed)
            // jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_task_master_department') THEN \n" +
            //         "ALTER TABLE task_master ADD CONSTRAINT fk_task_master_department FOREIGN KEY (department_code) REFERENCES department_master(department_code); END IF; END $$;");

            // Ensure department_master.is_register exists (for older DBs)
            jdbc.execute("ALTER TABLE IF EXISTS department_master ADD COLUMN IF NOT EXISTS is_register BOOLEAN NOT NULL DEFAULT FALSE");
            jdbc.execute("UPDATE department_master SET is_register = TRUE WHERE department_code = '520'");

            // task_plan: add department_code for per-department plans and ensure composite FK to task_master
            jdbc.execute("ALTER TABLE IF EXISTS task_plan ADD COLUMN IF NOT EXISTS department_code VARCHAR(32)");
            try {
                jdbc.execute("ALTER TABLE task_plan DROP CONSTRAINT IF EXISTS task_plan_task_code_fkey");
                jdbc.execute("ALTER TABLE task_plan DROP CONSTRAINT IF EXISTS fk_task_plan_task_master");
            } catch (Exception ignore) {}
            jdbc.execute("ALTER TABLE task_plan ADD CONSTRAINT fk_task_plan_task_master FOREIGN KEY (task_code, department_code) REFERENCES task_master(task_code, department_code)");
            // Optional FK to department_master (kept nullable for compatibility)
            // jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_task_plan_department') THEN \n" +
            //         "ALTER TABLE task_plan ADD CONSTRAINT fk_task_plan_department FOREIGN KEY (department_code) REFERENCES department_master(department_code); END IF; END $$;");

            // Create employee_task_skill table if missing
            jdbc.execute("CREATE TABLE IF NOT EXISTS employee_task_skill (" +
                    "employee_code VARCHAR(10) NOT NULL REFERENCES employee(employee_code)," +
                    "store_code VARCHAR(10)," +
                    "department_code VARCHAR(32)," +
                    "task_code VARCHAR(32) NOT NULL," +
                    "skill_level SMALLINT NOT NULL," +
                    "PRIMARY KEY (employee_code, task_code))");
            // Add new columns if migrating
            jdbc.execute("ALTER TABLE IF EXISTS employee_task_skill ADD COLUMN IF NOT EXISTS store_code VARCHAR(10)");
            jdbc.execute("ALTER TABLE IF EXISTS employee_task_skill ADD COLUMN IF NOT EXISTS department_code VARCHAR(32)");
            // Backfill department_code to legacy '520' where null
            jdbc.execute("UPDATE employee_task_skill SET department_code = '520' WHERE department_code IS NULL");
            // Drop legacy FK if exists and add composite FK
            try { jdbc.execute("ALTER TABLE employee_task_skill DROP CONSTRAINT IF EXISTS employee_task_skill_task_code_fkey"); } catch (Exception ignore) {}
            try { jdbc.execute("ALTER TABLE employee_task_skill DROP CONSTRAINT IF EXISTS fk_emp_task_skill_master"); } catch (Exception ignore) {}
            jdbc.execute("ALTER TABLE employee_task_skill ADD CONSTRAINT fk_emp_task_skill_master FOREIGN KEY (task_code, department_code) REFERENCES task_master(task_code, department_code)");

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

            // days_master (for legacy/special features; harmless if unused)
            jdbc.execute("CREATE TABLE IF NOT EXISTS days_master (" +
                    "days_id BIGSERIAL PRIMARY KEY, " +
                    "store_code VARCHAR(10) NOT NULL REFERENCES store(store_code), " +
                    "kind VARCHAR(8) NOT NULL CHECK (kind IN ('WEEKLY','SPECIAL')), " +
                    "day_of_week SMALLINT NULL CHECK (day_of_week BETWEEN 1 AND 7), " +
                    "special_date DATE NULL, " +
                    "label VARCHAR(64), " +
                    "active BOOLEAN NOT NULL DEFAULT TRUE, " +
                    "UNIQUE (store_code, kind, day_of_week, special_date)" +
                    ")");

            // Monthly task plan tables (DOM/WOM)
            jdbc.execute("CREATE TABLE IF NOT EXISTS monthly_task_plan (" +
                    "plan_id BIGSERIAL PRIMARY KEY, " +
                    "store_code VARCHAR(10) NOT NULL REFERENCES store(store_code), " +
                    "department_code VARCHAR(32), " +
                    "task_code VARCHAR(32) NOT NULL, " +
                    "schedule_type VARCHAR(10) CHECK (schedule_type IN ('FIXED','FLEXIBLE')), " +
                    "fixed_start_time TIME, fixed_end_time TIME, " +
                    "window_start_time TIME, window_end_time TIME, " +
                    "required_duration_minutes INT, required_staff_count INT, " +
                    "lane INT, must_be_contiguous SMALLINT, " +
                    "effective_from DATE, effective_to DATE, " +
                    "priority INT, note TEXT, active BOOLEAN NOT NULL DEFAULT TRUE" +
                    ")");
            // Composite FK to task_master
            try { jdbc.execute("ALTER TABLE monthly_task_plan DROP CONSTRAINT IF EXISTS fk_monthly_task_plan_task_master"); } catch (Exception ignore) {}
            jdbc.execute("ALTER TABLE monthly_task_plan ADD CONSTRAINT fk_monthly_task_plan_task_master FOREIGN KEY (task_code, department_code) REFERENCES task_master(task_code, department_code)");

            jdbc.execute("CREATE TABLE IF NOT EXISTS monthly_task_plan_dom (" +
                    "plan_id BIGINT NOT NULL REFERENCES monthly_task_plan(plan_id) ON DELETE CASCADE, " +
                    "day_of_month SMALLINT NOT NULL CHECK (day_of_month BETWEEN 1 AND 31), " +
                    "PRIMARY KEY (plan_id, day_of_month)" +
                    ")");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_monthly_task_plan_dom_plan ON monthly_task_plan_dom(plan_id)");

            jdbc.execute("CREATE TABLE IF NOT EXISTS monthly_task_plan_wom (" +
                    "plan_id BIGINT NOT NULL REFERENCES monthly_task_plan(plan_id) ON DELETE CASCADE, " +
                    "week_of_month SMALLINT NOT NULL CHECK (week_of_month BETWEEN 1 AND 5), " +
                    "day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7), " +
                    "PRIMARY KEY (plan_id, week_of_month, day_of_week)" +
                    ")");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_monthly_task_plan_wom_plan ON monthly_task_plan_wom(plan_id)");

            log.info("Schema checked/initialized: is_register column and employee_task_skill table ensured.");
        } catch (Exception e) {
            log.warn("Schema initialization failed: {}", e.getMessage());
        }
    }
}
