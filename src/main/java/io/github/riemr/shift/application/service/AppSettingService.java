package io.github.riemr.shift.application.service;

import io.github.riemr.shift.infrastructure.mapper.AppSettingMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.AppSetting;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
@RequiredArgsConstructor
public class AppSettingService {
    private static final String KEY_SHIFT_START_DAY = "shift_cycle_start_day";
    private static final String KEY_TIME_RES_MIN = "time_resolution_minutes";
    private final AppSettingMapper mapper;
    private final JdbcTemplate jdbc;

    public int getShiftCycleStartDay() {
        AppSetting s = mapper.selectByKey(KEY_SHIFT_START_DAY);
        if (s == null) return 1;
        try {
            int v = Integer.parseInt(s.getSettingValue());
            if (v < 1 || v > 28) return 1;
            return v;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @Transactional
    public void updateShiftCycleStartDay(int day) {
        if (day < 1 || day > 28) throw new IllegalArgumentException("start day must be 1..28");
        mapper.upsert(KEY_SHIFT_START_DAY, Integer.toString(day));
    }

    public int getTimeResolutionMinutes() {
        AppSetting s = mapper.selectByKey(KEY_TIME_RES_MIN);
        if (s == null) return 10;
        try {
            int v = Integer.parseInt(s.getSettingValue());
            return (v == 10 || v == 15) ? v : 10;
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    @Transactional
    public void updateTimeResolutionMinutes(int minutes) {
        if (minutes != 10 && minutes != 15) {
            throw new IllegalArgumentException("timeResolutionMinutes must be 10 or 15");
        }
        int current = getTimeResolutionMinutes();
        if (minutes != current && isTimeResolutionChangeLocked()) {
            throw new IllegalStateException("既存の需要データがあるため、timeResolutionMinutes は変更できません（現在: "
                    + current + " / 変更要求: " + minutes + "）。");
        }
        mapper.upsert(KEY_TIME_RES_MIN, Integer.toString(minutes));
    }

    public boolean isTimeResolutionChangeLocked() {
        // 変更禁止方針：需要intervalが存在するなら解像度は固定（データ移行をしないため）
        try {
            Integer regCount = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM register_demand_interval
                    """, Integer.class);
            if (regCount != null && regCount > 0) return true;

            Integer workCount = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM work_demand_interval
                    """, Integer.class);
            if (workCount != null && workCount > 0) return true;

            // 需要以外にも、スロット前提の割当が一度でも作られていれば変更不可
            Integer regAssignCount = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM register_assignment
                    """, Integer.class);
            if (regAssignCount != null && regAssignCount > 0) return true;

            Integer deptAssignCount = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM department_task_assignment
                    """, Integer.class);
            if (deptAssignCount != null && deptAssignCount > 0) return true;

            return false;
        } catch (DataAccessException e) {
            // 初期セットアップではテーブル未作成の可能性があるため、その場合は変更を許可する。
            // それ以外（接続不可など）は保存自体が失敗するが、安全側に倒して変更不可とする。
            return !looksLikeMissingTable(e);
        }
    }

    private static boolean looksLikeMissingTable(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg == null) continue;
            // PostgreSQL: relation "xxx" does not exist
            if (msg.contains("does not exist") && msg.contains("relation")) return true;
            // H2など: Table "XXX" not found
            if (msg.toLowerCase().contains("table") && msg.toLowerCase().contains("not found")) return true;
        }
        return false;
    }

    @Deprecated(forRemoval = true)
    private void assertDemandIntervalsAligned(int minutesPerSlot) {
        // 旧方針（整列チェックして変更許可）。現在は変更禁止のため未使用。
        try {
            Integer regMisaligned = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM register_demand_interval
                    WHERE (MOD(EXTRACT(MINUTE FROM from_time)::int, ?) <> 0)
                       OR (EXTRACT(SECOND FROM from_time)::int <> 0)
                       OR (MOD(EXTRACT(MINUTE FROM to_time)::int, ?) <> 0)
                       OR (EXTRACT(SECOND FROM to_time)::int <> 0)
                    """, Integer.class, minutesPerSlot, minutesPerSlot);
            if (regMisaligned != null && regMisaligned > 0) {
                throw new IllegalStateException("timeResolutionMinutes を " + minutesPerSlot
                        + " に変更できません。register_demand_interval に " + minutesPerSlot + " 分境界に揃っていないデータが "
                        + regMisaligned + " 件あります。");
            }

            Integer workMisaligned = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM work_demand_interval
                    WHERE (MOD(EXTRACT(MINUTE FROM from_time)::int, ?) <> 0)
                       OR (EXTRACT(SECOND FROM from_time)::int <> 0)
                       OR (MOD(EXTRACT(MINUTE FROM to_time)::int, ?) <> 0)
                       OR (EXTRACT(SECOND FROM to_time)::int <> 0)
                    """, Integer.class, minutesPerSlot, minutesPerSlot);
            if (workMisaligned != null && workMisaligned > 0) {
                throw new IllegalStateException("timeResolutionMinutes を " + minutesPerSlot
                        + " に変更できません。work_demand_interval に " + minutesPerSlot + " 分境界に揃っていないデータが "
                        + workMisaligned + " 件あります。");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // テーブル未作成/権限/方言差異などで検査できない場合は、既存動作を優先してスキップする。
        }
    }
}
