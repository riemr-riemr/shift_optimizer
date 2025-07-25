-- ===============================================
-- init.sql : DB schema for Register Shift Planner
-- ===============================================

SET client_encoding = 'UTF8';
SET search_path = public;

BEGIN;

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
-- 3. employee : 従業員マスタ
-- ------------------------------------------------
CREATE TABLE employee (
    employee_code        VARCHAR(10) PRIMARY KEY,
    store_code           VARCHAR(10) REFERENCES store(store_code),
    employee_name        VARCHAR(50) NOT NULL,
    short_follow         SMALLINT, -- 0~4
    max_work_minutes_day INT,
    max_work_days_month  INT
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
    employee_code VARCHAR(10),
    register_no   INT,
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