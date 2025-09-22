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

-- ------------------------------------------------
-- 3. employee : 従業員マスタ
-- ------------------------------------------------
CREATE TABLE employee (
    employee_code        VARCHAR(10) PRIMARY KEY,
    store_code           VARCHAR(10) REFERENCES store(store_code),
    employee_name        VARCHAR(50) NOT NULL,
    short_follow         SMALLINT, -- 0~4
    max_work_minutes_day INT,
    max_work_days_month  INT,
    password_hash        VARCHAR(100),
    authority_code       VARCHAR(20) REFERENCES authority_master(authority_code)
);

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

-- ------------------------------------------------
-- 6. register_demand_quarter : 15分単位需要
-- ------------------------------------------------
CREATE TABLE register_demand_quarter (
    demand_id      BIGSERIAL PRIMARY KEY,
    store_code     VARCHAR(10) REFERENCES store(store_code),
    demand_date    DATE NOT NULL,
    slot_time      TIME NOT NULL,
    required_units INT  NOT NULL
);
CREATE UNIQUE INDEX uq_demand ON register_demand_quarter(store_code, demand_date, slot_time);

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
-- 0. app_setting : 汎用アプリ設定
-- ------------------------------------------------
CREATE TABLE IF NOT EXISTS app_setting (
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
    employee_code, store_code, employee_name, short_follow,
    max_work_minutes_day, max_work_days_month, password_hash, authority_code
) VALUES (
    'admin', NULL, 'Administrator', 0,
    480, 31, crypt('admin', gen_salt('bf', 10)), 'ADMIN'
) ON CONFLICT (employee_code) DO NOTHING;
