-- ===============================================
-- init.sql : DB schema for Register Shift Planner
-- ===============================================

SET client_encoding = 'UTF8';
SET search_path = public;

BEGIN;

-- Extensions (for bcrypt hashing)
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ------------------------------------------------
-- 1. store : 店舗マスタ
-- ------------------------------------------------
CREATE TABLE store (
    store_code       VARCHAR(10) PRIMARY KEY,
    store_name       VARCHAR(100) NOT NULL,
    timezone         VARCHAR(30)  NOT NULL DEFAULT 'Asia/Tokyo'
);

-- ------------------------------------------------
-- 2. register : レジ定義
-- ------------------------------------------------
CREATE TABLE register_type (
    type_code        VARCHAR(10) PRIMARY KEY,
    type_name        VARCHAR(50) NOT NULL
);
CREATE TABLE register (
    store_code       VARCHAR(10) REFERENCES store(store_code),
    register_no      INT NOT NULL,
    register_name    VARCHAR(50) NOT NULL,
    short_name       VARCHAR(10),
    open_priority    INT NOT NULL DEFAULT 99,
    register_type    VARCHAR(10) NOT NULL,
    is_auto_open_target BOOLEAN NOT NULL DEFAULT FALSE,
    max_allowance    INT NOT NULL DEFAULT 60,
    PRIMARY KEY (store_code, register_no)
);

-- ------------------------------------------------
-- 2.5 authority_master : 権限マスタ
-- ------------------------------------------------
CREATE TABLE authority_master (
    authority_code   VARCHAR(20) PRIMARY KEY,
    authority_name   VARCHAR(50) NOT NULL,
    description      TEXT
);

INSERT INTO authority_master(authority_code, authority_name, description) VALUES
    ('ADMIN',   '管理者',          '全機能へのアクセス'),
    ('MANAGER', '店長/管理者',     '店舗運用・最適化・設定の一部'),
    ('USER',    '一般ユーザ',      '閲覧・自身に関する操作')
ON CONFLICT (authority_code) DO NOTHING;

-- 2.6 authority_screen_permission : 画面権限 (参照/更新)
CREATE TABLE IF NOT EXISTS authority_screen_permission (
    authority_code VARCHAR(20) REFERENCES authority_master(authority_code) ON DELETE CASCADE,
    screen_code    VARCHAR(50) NOT NULL,
    can_view       BOOLEAN NOT NULL DEFAULT FALSE,
    can_update     BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (authority_code, screen_code)
);

-- 既定権限付与
-- ADMIN: 全画面 参照/更新可
INSERT INTO authority_screen_permission (authority_code, screen_code, can_view, can_update) VALUES
 ('ADMIN','SHIFT_MONTHLY', true, true),
 ('ADMIN','SHIFT_DAILY',   true, true),
 ('ADMIN','EMPLOYEE_LIST', true, true),
 ('ADMIN','EMPLOYEE_REQUEST', true, true),
 ('ADMIN','SKILL_MATRIX',  true, true),
 ('ADMIN','REGISTER_DEMAND', true, true),
 ('ADMIN','STAFFING_BALANCE', true, true),
 ('ADMIN','SETTINGS',      true, true)
ON CONFLICT DO NOTHING;

-- MANAGER: シフト運用中心（設定は参照のみ）
INSERT INTO authority_screen_permission (authority_code, screen_code, can_view, can_update) VALUES
 ('MANAGER','SHIFT_MONTHLY', true, true),
 ('MANAGER','SHIFT_DAILY',   true, true),
 ('MANAGER','EMPLOYEE_LIST', true, false),
 ('MANAGER','EMPLOYEE_REQUEST', true, true),
 ('MANAGER','SKILL_MATRIX',  true, false),
 ('MANAGER','REGISTER_DEMAND', true, true),
 ('MANAGER','STAFFING_BALANCE', true, true),
 ('MANAGER','SETTINGS',      true, false)
ON CONFLICT DO NOTHING;

-- USER: 閲覧中心
INSERT INTO authority_screen_permission (authority_code, screen_code, can_view, can_update) VALUES
 ('USER','SHIFT_MONTHLY', true, false),
 ('USER','SHIFT_DAILY',   true, false),
 ('USER','EMPLOYEE_LIST', false, false),
 ('USER','EMPLOYEE_REQUEST', true, true),
 ('USER','SKILL_MATRIX',  false, false),
 ('USER','REGISTER_DEMAND', false, false),
 ('USER','STAFFING_BALANCE', true, false),
 ('USER','SETTINGS',      false, false)
ON CONFLICT DO NOTHING;

-- Screen permission admin screen
INSERT INTO authority_screen_permission (authority_code, screen_code, can_view, can_update) VALUES
 ('ADMIN','SCREEN_PERMISSION', true, true),
 ('ADMIN','CSV_IMPORT', true, true),
 ('MANAGER','SCREEN_PERMISSION', false, false),
 ('MANAGER','CSV_IMPORT', false, false),
 ('USER','SCREEN_PERMISSION', false, false)
 ,('USER','CSV_IMPORT', false, false)
ON CONFLICT DO NOTHING;

-- ------------------------------------------------
-- 3. employee : 従業員マスタ
-- ------------------------------------------------
CREATE TABLE employee (
    employee_code         VARCHAR(10) PRIMARY KEY,
    store_code            VARCHAR(10) REFERENCES store(store_code),
    employee_name         VARCHAR(50) NOT NULL,
    min_work_minutes_day  INT,
    max_work_minutes_day  INT,
    -- 週次の最小・最大勤務時間（時）
    min_work_hours_week   INT,
    max_work_hours_week   INT,
    password_hash         VARCHAR(100),
    authority_code        VARCHAR(20) REFERENCES authority_master(authority_code)
);

-- 従業員の月次公休日数設定（最小・最大）
-- 従業員の月次設定を集約（労働時間・公休日数）
-- 月次設定は労働時間/公休日数で分離し、互換ビューで集約
CREATE TABLE IF NOT EXISTS employee_monthly_hours_setting (
    employee_code   VARCHAR(10) NOT NULL REFERENCES employee(employee_code) ON DELETE CASCADE,
    month_start     DATE        NOT NULL, -- 対象月の1日
    min_work_hours  INTEGER,
    max_work_hours  INTEGER,
    PRIMARY KEY (employee_code, month_start)
);

CREATE TABLE IF NOT EXISTS employee_monthly_offdays_setting (
    employee_code   VARCHAR(10) NOT NULL REFERENCES employee(employee_code) ON DELETE CASCADE,
    month_start     DATE        NOT NULL,
    min_off_days    INTEGER,
    max_off_days    INTEGER,
    PRIMARY KEY (employee_code, month_start)
);

CREATE OR REPLACE VIEW employee_monthly_setting AS
SELECT 
  COALESCE(h.employee_code, o.employee_code) AS employee_code,
  COALESCE(h.month_start,  o.month_start)    AS month_start,
  h.min_work_hours,
  h.max_work_hours,
  o.min_off_days,
  o.max_off_days
FROM employee_monthly_hours_setting h
FULL OUTER JOIN employee_monthly_offdays_setting o
  ON h.employee_code = o.employee_code AND h.month_start = o.month_start;

-- 従業員の曜日別勤務設定
CREATE TABLE employee_weekly_preference (
    employee_code   VARCHAR(10) NOT NULL REFERENCES employee(employee_code),
    day_of_week     SMALLINT    NOT NULL CHECK (day_of_week BETWEEN 1 AND 7), -- 1=Mon ... 7=Sun (ISO)
    work_style      VARCHAR(16) NOT NULL CHECK (work_style IN ('OFF','OPTIONAL','MANDATORY')),
    base_start_time TIME NULL,
    base_end_time   TIME NULL,
    store_code      VARCHAR(10) NULL,
    created_at      TIMESTAMP   DEFAULT now(),
    updated_at      TIMESTAMP   DEFAULT now(),
    PRIMARY KEY (employee_code, day_of_week),
    CHECK ( (work_style = 'OFF' AND base_start_time IS NULL AND base_end_time IS NULL) OR (work_style <> 'OFF') )
);

-- シフトパターン（開始〜終了、曜日、活性）
CREATE TABLE IF NOT EXISTS shift_pattern (
    pattern_code   VARCHAR(32) PRIMARY KEY,
    start_time     TIME        NOT NULL,
    end_time       TIME        NOT NULL,
    active         BOOLEAN     NOT NULL DEFAULT TRUE
);

-- 従業員×シフトパターン（多対多、中間）
CREATE TABLE IF NOT EXISTS employee_shift_pattern (
    employee_code  VARCHAR(10) NOT NULL REFERENCES employee(employee_code) ON DELETE CASCADE,
    pattern_code   VARCHAR(32) NOT NULL REFERENCES shift_pattern(pattern_code) ON DELETE CASCADE,
    priority       SMALLINT,
    active         BOOLEAN     NOT NULL DEFAULT TRUE,
    PRIMARY KEY (employee_code, pattern_code),
    CHECK (priority IS NULL OR (priority BETWEEN 0 AND 4))
);
CREATE INDEX IF NOT EXISTS idx_emp_shift_pattern_emp ON employee_shift_pattern(employee_code);

-- ------------------------------------------------
-- 5. employee_register_skill : 従業員×レジ習熟度
-- ------------------------------------------------
CREATE TABLE employee_register_skill (
    store_code     VARCHAR(10)   NOT NULL,
    employee_code  VARCHAR(10)   NOT NULL,
    register_no    INT           NOT NULL,
    skill_level    SMALLINT,         -- 0~4
    PRIMARY KEY (store_code, employee_code, register_no),

    FOREIGN KEY (store_code, register_no)
        REFERENCES register(store_code, register_no),
    FOREIGN KEY (employee_code)
        REFERENCES employee(employee_code)
);

-- 6. register_demand_interval : 区間需要 [from, to)
CREATE TABLE IF NOT EXISTS register_demand_interval (
    id            BIGSERIAL PRIMARY KEY,
    store_code    VARCHAR(10) NOT NULL REFERENCES store(store_code),
    target_date   DATE        NOT NULL,
    from_time     TIME        NOT NULL,
    to_time       TIME        NOT NULL,
    demand        INTEGER     NOT NULL,
    register_no   INT         NOT NULL REFERENCES register(store_code, register_no)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_register_demand_interval
  ON register_demand_interval (store_code, target_date, from_time, to_time, register_no);
CREATE INDEX IF NOT EXISTS idx_register_demand_interval_date
  ON register_demand_interval (store_code, target_date);

-- ------------------------------------------------
-- 7. shift_assignment : 生成済みシフト
-- ------------------------------------------------
CREATE TABLE shift_assignment (
    shift_id      BIGSERIAL PRIMARY KEY,
    store_code    VARCHAR(10) NOT NULL,
    employee_code VARCHAR(10) NOT NULL,
    start_at      TIMESTAMP   NOT NULL,
    end_at        TIMESTAMP   NOT NULL,
    created_by    VARCHAR(20) NOT NULL DEFAULT 'auto',

    FOREIGN KEY (employee_code)
        REFERENCES employee(employee_code),
    UNIQUE (store_code, employee_code, start_at)
);

-- ------------------------------------------------
-- 7a. register_assignment : 生成済みレジ割り当て
-- ------------------------------------------------
CREATE TABLE register_assignment (
    assignment_id      BIGSERIAL PRIMARY KEY,
    store_code    VARCHAR(10) NOT NULL,
    employee_code VARCHAR(10) NOT NULL,
    register_no   INT NOT NULL,
    start_at      TIMESTAMP   NOT NULL,
    end_at        TIMESTAMP   NOT NULL,
    created_by    VARCHAR(20) NOT NULL DEFAULT 'auto',

    FOREIGN KEY (store_code, register_no)
        REFERENCES register(store_code, register_no),
    FOREIGN KEY (employee_code)
        REFERENCES employee(employee_code),
    UNIQUE (store_code, employee_code, start_at)
);

-- ------------------------------------------------
-- 8. employee_request : 希望休・出勤希望
-- ------------------------------------------------
CREATE TABLE employee_request (
    request_id     BIGSERIAL PRIMARY KEY,
    store_code     VARCHAR(10) NOT NULL,
    employee_code  VARCHAR(10) NOT NULL,
    request_date   DATE NOT NULL,
    from_time      TIME,
    to_time        TIME,
    request_kind   VARCHAR(12) NOT NULL
                   CHECK (request_kind IN ('off','unavailable','prefer_on')),
    priority       INT NOT NULL DEFAULT 2,
    note           TEXT,

    FOREIGN KEY (employee_code)
        REFERENCES employee(employee_code),
    UNIQUE (store_code, employee_code, request_date, from_time, to_time, request_kind)
);

-- ------------------------------------------------
-- 9. constraint_master : 制約マスタ
-- ------------------------------------------------
CREATE TABLE constraint_master (
    constraint_code  VARCHAR(40) PRIMARY KEY,
    description      TEXT,
    default_kind     VARCHAR(8) NOT NULL CHECK (default_kind IN ('HARD','SOFT')),
    default_weight   INT NOT NULL DEFAULT 0
);

INSERT INTO constraint_master (constraint_code, description, default_kind, default_weight) VALUES
 ('MAX_CONSEC_DAYS',        '同一従業員の連続勤務最大日数',      'HARD',0),
 ('MIN_REST_MINUTES',       '勤務間インターバル(分)',             'HARD',0),
 ('MAX_WORK_MIN_MONTH',     '1か月総労働分の上限',               'HARD',0),
 ('PENALTY_UNASSIGNED_SLOT','必要台数を満たせない15分スロット',   'SOFT',100),
 ('PENALTY_REQUEST_OFF',    '希望休を割り当ててしまった場合',     'SOFT',50)
ON CONFLICT DO NOTHING;

-- ------------------------------------------------
-- 10. constraint_setting : 制約値／重みオーバーライド
-- ------------------------------------------------
CREATE TABLE constraint_setting (
    setting_id       BIGSERIAL PRIMARY KEY,
    constraint_code  VARCHAR(40) REFERENCES constraint_master(constraint_code),
    scope_type       VARCHAR(12) NOT NULL CHECK (scope_type IN ('GLOBAL','STORE','EMPLOYEE')),
    scope_id         VARCHAR(20),
    numeric_value    DECIMAL(10,2),
    text_value       TEXT,
    weight_override  INT,
    effective_from   DATE,
    effective_to     DATE,
    CHECK ( (scope_type='GLOBAL' AND scope_id IS NULL) OR (scope_type<>'GLOBAL' AND scope_id IS NOT NULL) )
);

COMMIT;

-- end of init.sql
-- ------------------------------------------------
-- 11. department_master / store_department / employee_department / skills
-- ------------------------------------------------
CREATE TABLE IF NOT EXISTS department_master (
  department_code   VARCHAR(32) PRIMARY KEY,
  department_name   TEXT        NOT NULL,
  display_order     INTEGER,
  is_active         BOOLEAN     NOT NULL DEFAULT TRUE,
  is_register       BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS store_department (
  store_code        VARCHAR(10) NOT NULL REFERENCES store(store_code),
  department_code   VARCHAR(32) NOT NULL REFERENCES department_master(department_code),
  display_order     INTEGER,
  is_active         BOOLEAN     NOT NULL DEFAULT TRUE,
  PRIMARY KEY (store_code, department_code)
);

CREATE TABLE IF NOT EXISTS employee_department (
  employee_code     VARCHAR(10) NOT NULL REFERENCES employee(employee_code),
  department_code   VARCHAR(32) NOT NULL REFERENCES department_master(department_code),
  PRIMARY KEY (employee_code, department_code)
);

CREATE TABLE IF NOT EXISTS employee_department_skill (
  employee_code     VARCHAR(10) NOT NULL REFERENCES employee(employee_code),
  department_code   VARCHAR(32) NOT NULL REFERENCES department_master(department_code),
  skill_level       SMALLINT    NOT NULL,
  PRIMARY KEY (employee_code, department_code)
);

-- 12. work_demand_interval (non-register demand)
CREATE TABLE IF NOT EXISTS work_demand_interval (
  id               BIGSERIAL   PRIMARY KEY,
  store_code        VARCHAR(10) NOT NULL REFERENCES store(store_code),
  department_code   VARCHAR(32) NOT NULL REFERENCES department_master(department_code),
  target_date       DATE        NOT NULL,
  from_time         TIME        NOT NULL,
  to_time           TIME        NOT NULL,
  demand            INTEGER     NOT NULL,
  task_code         VARCHAR(32)
);
CREATE INDEX IF NOT EXISTS idx_work_demand_interval_date
  ON work_demand_interval (store_code, department_code, target_date);

-- ------------------------------------------------
-- 12b. attendance_group_constraint / attendance_group_member
-- ------------------------------------------------
CREATE TABLE IF NOT EXISTS attendance_group_constraint (
  constraint_id   BIGSERIAL PRIMARY KEY,
  store_code      VARCHAR(10) NOT NULL REFERENCES store(store_code),
  department_code VARCHAR(32) NULL REFERENCES department_master(department_code),
  rule_type       VARCHAR(32) NOT NULL,
  min_on_duty     INTEGER,
  created_at      TIMESTAMPTZ DEFAULT now(),
  updated_at      TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_attendance_group_constraint_scope
  ON attendance_group_constraint (store_code, department_code);

CREATE TABLE IF NOT EXISTS attendance_group_member (
  constraint_id   BIGINT NOT NULL REFERENCES attendance_group_constraint(constraint_id) ON DELETE CASCADE,
  employee_code   VARCHAR(10) NOT NULL REFERENCES employee(employee_code),
  PRIMARY KEY (constraint_id, employee_code)
);
CREATE INDEX IF NOT EXISTS idx_attendance_group_member_employee
  ON attendance_group_member (employee_code);

-- ------------------------------------------------
-- 12a. department_task_assignment (non-register assignments)
-- ------------------------------------------------
CREATE TABLE IF NOT EXISTS department_task_assignment (
  assignment_id    BIGSERIAL PRIMARY KEY,
  store_code       VARCHAR(10) NOT NULL REFERENCES store(store_code),
  department_code  VARCHAR(32) NOT NULL REFERENCES department_master(department_code),
  task_code        VARCHAR(32),
  employee_code    VARCHAR(10) NULL REFERENCES employee(employee_code),
  start_at         TIMESTAMP   NOT NULL,
  end_at           TIMESTAMP   NOT NULL,
  created_by       VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_dept_task_assign_sdd ON department_task_assignment (store_code, department_code, start_at);

-- ------------------------------------------------
-- 12b. task_assignment view (register + department task assignments)
-- ------------------------------------------------
DROP VIEW IF EXISTS task_assignment;
CREATE VIEW task_assignment AS
  SELECT assignment_id,
         NULL::BIGINT AS task_id,
         employee_code,
         start_at,
         end_at,
         'REGISTER'::VARCHAR AS source,
         NULL::VARCHAR AS status,
         created_by,
         NULL::TIMESTAMPTZ AS created_at
  FROM register_assignment
  UNION ALL
  SELECT assignment_id,
         NULL::BIGINT AS task_id,
         employee_code,
         start_at,
         end_at,
         'DEPARTMENT_TASK'::VARCHAR AS source,
         NULL::VARCHAR AS status,
         created_by,
         NULL::TIMESTAMPTZ AS created_at
  FROM department_task_assignment;

-- Optional: register_assignment department tagging
-- ALTER TABLE register_assignment ADD COLUMN department_code VARCHAR(32);
-- UPDATE register_assignment SET department_code = 'REGISTER' WHERE department_code IS NULL;
-- ALTER TABLE register_assignment ALTER COLUMN department_code SET NOT NULL;
-- CREATE INDEX IF NOT EXISTS idx_register_assignment_sdd ON register_assignment (store_code, department_code, start_at);

-- ------------------------------------------------
-- 0. app_setting : 汎用アプリ設定
-- ------------------------------------------------
CREATE TABLE app_setting (
    setting_key   VARCHAR(64) PRIMARY KEY,
    setting_value VARCHAR(256) NOT NULL,
    updated_at    TIMESTAMP NOT NULL DEFAULT now()
);
INSERT INTO app_setting(setting_key, setting_value)
    VALUES ('shift_cycle_start_day','1')
    ON CONFLICT (setting_key) DO NOTHING;

-- ------------------------------------------------
-- Seed: initial admin user (dev convenience)
--   username: admin / password: admin
--   NOTE: Change password after first login.
-- ------------------------------------------------
INSERT INTO employee(
    employee_code, store_code, employee_name,
    min_work_minutes_day, max_work_minutes_day,
    min_work_hours_week,  max_work_hours_week,
    password_hash, authority_code
) VALUES (
    'admin', NULL, 'Administrator',
    0, 480,
    0, 50,
    crypt('admin', gen_salt('bf', 10)), 'ADMIN'
) ON CONFLICT (employee_code) DO NOTHING;

-- 月次の最小・最大勤務時間は年月別テーブルで管理
-- 各従業員・各月の時間(時)を保持する
-- 例: 管理者のデフォルト月次設定（必要に応じて調整）
INSERT INTO employee_monthly_hours_setting (employee_code, month_start, min_work_hours, max_work_hours)
VALUES ('admin', date_trunc('month', CURRENT_DATE)::date, 0, 200)
ON CONFLICT DO NOTHING;

-- 週次の最小・最大勤務時間はemployeeテーブルで管理

-- ------------------------------------------------
-- 13. 権限: Task画面の初期権限
-- ------------------------------------------------
INSERT INTO authority_screen_permission (authority_code, screen_code, can_view, can_update) VALUES
 ('ADMIN','TASKS', true, true)
ON CONFLICT DO NOTHING;
INSERT INTO authority_screen_permission (authority_code, screen_code, can_view, can_update) VALUES
 ('MANAGER','TASKS', true, true)
ON CONFLICT DO NOTHING;
INSERT INTO authority_screen_permission (authority_code, screen_code, can_view, can_update) VALUES
 ('USER','TASKS', false, false)
ON CONFLICT DO NOTHING;

-- New screen: Department Skill Matrix (non-register)
INSERT INTO authority_screen_permission (authority_code, screen_code, can_view, can_update) VALUES
 ('ADMIN','DEPT_SKILL_MATRIX', true, true)
ON CONFLICT DO NOTHING;
INSERT INTO authority_screen_permission (authority_code, screen_code, can_view, can_update) VALUES
 ('MANAGER','DEPT_SKILL_MATRIX', true, false)
ON CONFLICT DO NOTHING;
INSERT INTO authority_screen_permission (authority_code, screen_code, can_view, can_update) VALUES
 ('USER','DEPT_SKILL_MATRIX', false, false)
ON CONFLICT DO NOTHING;

-- ------------------------------------------------
-- 14. Task Master / Weekly Plan / Special Day
-- ------------------------------------------------
-- 14-0. task_category_master : 作業カテゴリマスタ
CREATE TABLE IF NOT EXISTS task_category_master (
    category_code   VARCHAR(32) PRIMARY KEY,
    category_name   VARCHAR(100) NOT NULL,
    display_order   INTEGER,
    color           VARCHAR(16),
    icon            VARCHAR(64),
    active          BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS task_master (
    task_code                    VARCHAR(32),
    department_code              VARCHAR(32),
    category_code                VARCHAR(32) NULL REFERENCES task_category_master(category_code),
    name                         VARCHAR(100) NOT NULL,
    description                  TEXT,
    default_schedule_type        VARCHAR(10) CHECK (default_schedule_type IN ('FIXED','FLEXIBLE')),
    default_required_duration_minutes INT,
    priority                     INT,
    color                        VARCHAR(16),
    icon                         VARCHAR(64),
    PRIMARY KEY (task_code, department_code)
);
CREATE INDEX IF NOT EXISTS idx_task_master_category ON task_master(category_code);

-- 14-1. task : 非レジの作業タスク（週次・月次計画から展開された実体）
CREATE TABLE IF NOT EXISTS task (
    task_id                 BIGSERIAL PRIMARY KEY,
    store_code              VARCHAR(10) NOT NULL REFERENCES store(store_code),
    work_date               DATE        NOT NULL,
    name                    VARCHAR(100) NOT NULL,
    description             TEXT,
    schedule_type           VARCHAR(10) CHECK (schedule_type IN ('FIXED','FLEXIBLE')),
    fixed_start_at          TIMESTAMP,
    fixed_end_at            TIMESTAMP,
    window_start_at         TIMESTAMP,
    window_end_at           TIMESTAMP,
    required_duration_minutes INT,
    required_skill_code     VARCHAR(32),
    required_staff_count    INT,
    priority                INT,
    must_be_contiguous      SMALLINT,
    created_by              VARCHAR(64) DEFAULT 'auto',
    created_at              TIMESTAMP   DEFAULT now(),
    updated_by              VARCHAR(64) DEFAULT 'auto',
    updated_at              TIMESTAMP   DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_task_store_date ON task(store_code, work_date);

-- 統合: task_plan（曜日別/特異日を1テーブルに集約）
CREATE TABLE IF NOT EXISTS task_plan (
    plan_id              BIGSERIAL PRIMARY KEY,
    store_code           VARCHAR(10) NOT NULL REFERENCES store(store_code),
    department_code      VARCHAR(32) NULL REFERENCES department_master(department_code),
    task_code            VARCHAR(32) NOT NULL,
    day_of_week          SMALLINT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    schedule_type        VARCHAR(10) NULL CHECK (schedule_type IN ('FIXED','FLEXIBLE')),
    fixed_start_time     TIME,
    fixed_end_time       TIME,
    window_start_time    TIME,
    window_end_time      TIME,
    required_duration_minutes INT,
    required_staff_count INT,
    lane                 INT NULL,
    must_be_contiguous   SMALLINT,
    effective_from       DATE NULL,
    effective_to         DATE NULL,
    priority             INT,
    note                 TEXT,
    active               BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX IF NOT EXISTS idx_task_plan_store_dow ON task_plan(store_code, day_of_week);

-- Monthly Task Plan (DOM/WOM patterns)
-- DOM: specific days of month (1..31)
-- WOM: week-of-month (1..5) x ISO day-of-week (1..7)
CREATE TABLE IF NOT EXISTS monthly_task_plan (
  plan_id               BIGSERIAL PRIMARY KEY,
  store_code            VARCHAR(10) NOT NULL REFERENCES store(store_code),
  department_code       VARCHAR(32) NULL REFERENCES department_master(department_code),
  task_code             VARCHAR(32) NOT NULL,
  schedule_type         VARCHAR(10) NULL CHECK (schedule_type IN ('FIXED','FLEXIBLE')),
  fixed_start_time      TIME,
  fixed_end_time        TIME,
  window_start_time     TIME,
  window_end_time       TIME,
  required_duration_minutes INT,
  required_staff_count  INT,
  lane                  INT NULL,
  must_be_contiguous    SMALLINT,
  effective_from        DATE NULL,
  effective_to          DATE NULL,
  priority              INT,
  note                  TEXT,
  active                BOOLEAN NOT NULL DEFAULT TRUE
);
ALTER TABLE IF EXISTS monthly_task_plan
  ADD CONSTRAINT IF NOT EXISTS fk_monthly_task_plan_task_master
  FOREIGN KEY (task_code, department_code) REFERENCES task_master(task_code, department_code);

-- Day-of-month selections (1..31)
CREATE TABLE IF NOT EXISTS monthly_task_plan_dom (
  plan_id        BIGINT NOT NULL REFERENCES monthly_task_plan(plan_id) ON DELETE CASCADE,
  day_of_month   SMALLINT NOT NULL CHECK (day_of_month BETWEEN 1 AND 31),
  PRIMARY KEY (plan_id, day_of_month)
);
CREATE INDEX IF NOT EXISTS idx_monthly_task_plan_dom_plan ON monthly_task_plan_dom(plan_id);

-- Week-of-month (1..5) x ISO day-of-week (1..7)
CREATE TABLE IF NOT EXISTS monthly_task_plan_wom (
  plan_id        BIGINT NOT NULL REFERENCES monthly_task_plan(plan_id) ON DELETE CASCADE,
  week_of_month  SMALLINT NOT NULL CHECK (week_of_month BETWEEN 1 AND 5),
  day_of_week    SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
  PRIMARY KEY (plan_id, week_of_month, day_of_week)
);
CREATE INDEX IF NOT EXISTS idx_monthly_task_plan_wom_plan ON monthly_task_plan_wom(plan_id);

-- days_master（UIで選択する「曜日/特異日」のマスタ）
CREATE TABLE IF NOT EXISTS days_master (
    days_id      BIGSERIAL PRIMARY KEY,
    store_code   VARCHAR(10) NOT NULL REFERENCES store(store_code),
    kind         VARCHAR(8)  NOT NULL CHECK (kind IN ('WEEKLY','SPECIAL')),
    day_of_week  SMALLINT    NULL CHECK (day_of_week BETWEEN 1 AND 7),
    special_date DATE        NULL,
    label        VARCHAR(64),
    active       BOOLEAN     NOT NULL DEFAULT TRUE,
    UNIQUE (store_code, kind, day_of_week, special_date)
);

-- Employee x Task skill levels (for non-register tasks)
CREATE TABLE IF NOT EXISTS employee_task_skill (
  employee_code   VARCHAR(10)  NOT NULL REFERENCES employee(employee_code),
  store_code      VARCHAR(10),
  department_code VARCHAR(32),
  task_code       VARCHAR(32)  NOT NULL,
  skill_level     SMALLINT     NOT NULL,
  PRIMARY KEY (employee_code, task_code)
);

-- Composite FK to task_master(task_code, department_code)
ALTER TABLE IF EXISTS employee_task_skill
  ADD CONSTRAINT IF NOT EXISTS fk_emp_task_skill_master
  FOREIGN KEY (task_code, department_code) REFERENCES task_master(task_code, department_code);

-- 権限シード: マスタ/計画/特異日
INSERT INTO authority_screen_permission (authority_code, screen_code, can_view, can_update) VALUES
 ('ADMIN','TASK_MASTER', true, true)
ON CONFLICT DO NOTHING;
INSERT INTO authority_screen_permission (authority_code, screen_code, can_view, can_update) VALUES
 ('MANAGER','TASK_MASTER', true, true)
ON CONFLICT DO NOTHING;
INSERT INTO authority_screen_permission (authority_code, screen_code, can_view, can_update) VALUES
 ('USER','TASK_MASTER', false, false)
ON CONFLICT DO NOTHING;

INSERT INTO authority_screen_permission (authority_code, screen_code, can_view, can_update) VALUES
 ('ADMIN','TASK_PLAN', true, true)
ON CONFLICT DO NOTHING;
INSERT INTO authority_screen_permission (authority_code, screen_code, can_view, can_update) VALUES
 ('MANAGER','TASK_PLAN', true, true)
ON CONFLICT DO NOTHING;
INSERT INTO authority_screen_permission (authority_code, screen_code, can_view, can_update) VALUES
 ('USER','TASK_PLAN', false, false)
ON CONFLICT DO NOTHING;

-- ------------------------------------------------
-- 15. task: 生成された部門タスク（作業）リクエスト
-- ------------------------------------------------
CREATE TABLE IF NOT EXISTS task (
    task_id                    BIGSERIAL PRIMARY KEY,
    store_code                 VARCHAR(10) NOT NULL REFERENCES store(store_code),
    work_date                  DATE        NOT NULL,
    name                       VARCHAR(100),
    description                TEXT,
    schedule_type              VARCHAR(10) CHECK (schedule_type IN ('FIXED','FLEXIBLE')),
    fixed_start_at             TIMESTAMP,
    fixed_end_at               TIMESTAMP,
    window_start_at            TIMESTAMP,
    window_end_at              TIMESTAMP,
    required_duration_minutes  INTEGER,
    required_skill_code        VARCHAR(32),
    required_staff_count       INTEGER,
    priority                   INTEGER,
    must_be_contiguous         SMALLINT,
    created_by                 VARCHAR(64),
    created_at                 TIMESTAMP DEFAULT now(),
    updated_by                 VARCHAR(64),
    updated_at                 TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_task_store_date ON task(store_code, work_date);
ALTER TABLE IF EXISTS task_plan
  ADD CONSTRAINT IF NOT EXISTS fk_task_plan_task_master
  FOREIGN KEY (task_code, department_code) REFERENCES task_master(task_code, department_code);
