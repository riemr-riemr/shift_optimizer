package io.github.riemr.shift.optimization.service;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference;
import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeShiftPattern;
import io.github.riemr.shift.infrastructure.persistence.entity.ShiftAssignment;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import io.github.riemr.shift.optimization.solution.ShiftSchedule;
import io.github.riemr.shift.infrastructure.mapper.ShiftAssignmentMapper;
import io.github.riemr.shift.optimization.entity.WorkKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssignmentService {

    private final ShiftAssignmentMapper shiftAssignmentMapper;

    public void prepareCandidateEmployeesForAssignment(ShiftSchedule schedule, LocalDate cycleStart) {
        final var employees = schedule.getEmployeeList();
        if (schedule.getAssignmentList() == null || employees == null) return;
        final var requests = Optional.ofNullable(schedule.getEmployeeRequestList()).orElse(List.of());
        final var weekly = Optional.ofNullable(schedule.getEmployeeWeeklyPreferenceList()).orElse(List.of());

        Map<String, Set<LocalDate>> offDatesByEmp = new HashMap<>();
        for (var r : requests) {
            if (r.getRequestKind() == null) continue;
            String kind = r.getRequestKind().toLowerCase();
            if ("off".equals(kind) || "paid_leave".equals(kind)) {
                LocalDate d = r.getRequestDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                offDatesByEmp.computeIfAbsent(r.getEmployeeCode(), k -> new java.util.HashSet<>()).add(d);
            }
        }
        Map<String, Set<Integer>> weeklyOffByEmp = new HashMap<>();
        Map<String, Map<Integer, EmployeeWeeklyPreference>> weeklyPrefByEmpDow = new HashMap<>();
        for (var p : weekly) {
            weeklyPrefByEmpDow.computeIfAbsent(p.getEmployeeCode(), k -> new HashMap<>())
                    .put(p.getDayOfWeek().intValue(), p);
            if ("OFF".equalsIgnoreCase(p.getWorkStyle()))
                weeklyOffByEmp.computeIfAbsent(p.getEmployeeCode(), k -> new java.util.HashSet<>()).add(p.getDayOfWeek().intValue());
        }

        LocalDate cycleEnd = cycleStart.plusMonths(1);
        List<ShiftAssignment> attendance = shiftAssignmentMapper.selectByMonth(cycleStart, cycleEnd);
        if (schedule.getStoreCode() != null) {
            attendance = attendance.stream().filter(sa -> schedule.getStoreCode().equals(sa.getStoreCode())).toList();
        }
        final int maxConsecutiveDays = 6;
        Map<String, Set<LocalDate>> attendanceDaysByEmp = new HashMap<>();
        for (var sa : attendance) {
            if (sa.getEmployeeCode() == null || sa.getStartAt() == null) continue;
            LocalDate d = sa.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            attendanceDaysByEmp.computeIfAbsent(sa.getEmployeeCode(), k -> new java.util.HashSet<>()).add(d);
        }

        Map<String, List<EmployeeShiftPattern>> patternByEmp =
                Optional.ofNullable(schedule.getEmployeeShiftPatternList()).orElse(List.of())
                        .stream().collect(Collectors.groupingBy(EmployeeShiftPattern::getEmployeeCode));

        Map<String, Map<Integer, Short>> regSkillByEmpRegister = new HashMap<>();
        for (var sk : Optional.ofNullable(schedule.getEmployeeRegisterSkillList()).orElse(List.of())) {
            regSkillByEmpRegister
                    .computeIfAbsent(sk.getEmployeeCode(), k -> new HashMap<>())
                    .put(sk.getRegisterNo(), sk.getSkillLevel());
        }
        Map<String, Map<String, Short>> deptSkillByEmpDept = new HashMap<>();
        for (var sk : Optional.ofNullable(schedule.getEmployeeDepartmentSkillList()).orElse(List.of())) {
            deptSkillByEmpDept
                    .computeIfAbsent(sk.getEmployeeCode(), k -> new HashMap<>())
                    .put(sk.getDepartmentCode(), sk.getSkillLevel());
        }

        // 既存ロスター（ATTENDANCE）の有無に関わらず、候補はon-duty重なり or 週次設定で広く許容する
        // assignedEmployeeCodes に限定してしまうと、ATTENDANCE未実行時に候補が消えるため除去

        var assignments = new ArrayList<>(schedule.getAssignmentList());
        int originalCount = assignments.size();
        List<ShiftAssignmentPlanningEntity> toRemove = new ArrayList<>();

        for (var a : assignments) {
            LocalDate date = a.getShiftDate();
            List<Employee> cands;

            var start = a.getStartAt().toInstant();
            var end = a.getEndAt().toInstant();
            Set<String> onDuty = new HashSet<>();
            for (var sa : attendance) {
                if (sa.getStartAt() == null || sa.getEndAt() == null) continue;
                var s = sa.getStartAt().toInstant();
                var e = sa.getEndAt().toInstant();
                // タスク時間が出勤時間に完全に包含される場合のみ候補とする
                boolean fullyContained = !start.isBefore(s) && !end.isAfter(e);
                if (fullyContained) onDuty.add(sa.getEmployeeCode());
            }

            if (!onDuty.isEmpty()) {
                cands = employees.stream()
                        .filter(e -> onDuty.contains(e.getEmployeeCode()))
                        .filter(e -> withinWeeklyBase(weeklyPrefByEmpDow.get(e.getEmployeeCode()), date,
                                a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime(),
                                a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime()))
                        .filter(e -> matchesAnyPattern(patternByEmp.get(e.getEmployeeCode()), date,
                                a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime(),
                                a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime()))
                        .filter(e -> { // スキル0/1除外
                            String code = e.getEmployeeCode();
                            if (a.getWorkKind() == WorkKind.REGISTER_OP) {
                                Integer rn = a.getRegisterNo();
                                if (rn != null) {
                                    Short lv = regSkillByEmpRegister.getOrDefault(code, Map.of()).get(rn);
                                    if (lv != null && (lv == 0 || lv == 1)) return false;
                                }
                            } else if (a.getWorkKind() == WorkKind.DEPARTMENT_TASK) {
                                String dept = a.getDepartmentCode();
                                if (dept != null) {
                                    Short lv = deptSkillByEmpDept.getOrDefault(code, Map.of()).get(dept);
                                    if (lv != null && (lv == 0 || lv == 1)) return false;
                                }
                            }
                            return true;
                        })
                        .filter(e -> !wouldExceedConsecutiveCap(attendanceDaysByEmp.getOrDefault(e.getEmployeeCode(), Set.of()), date, maxConsecutiveDays))
                        .toList();
            } else {
                // フォールバック処理では出勤時間が設定されていない場合のみ実行
                // 出勤時間外への割り当てを防止するため、出勤データがない場合は候補を空にする
                cands = new ArrayList<>();
                if (log.isInfoEnabled()) {
                    log.info("ASSIGNMENT: No on-duty roster for date={} time={}~{}. No candidates to prevent assignment outside shift time.",
                            date, 
                            a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime(),
                            a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime());
                }
            }

            a.setCandidateEmployees(cands);
            if (cands == null || cands.isEmpty()) {
                var st = a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime();
                var et = a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime();
                log.warn("ASSIGNMENT no candidates; removing slot date={} time={}~{} kind={} registerNo={} store={} dept={}",
                        date, st, et, a.getWorkKind(), a.getRegisterNo(), schedule.getStoreCode(), schedule.getDepartmentCode());
                toRemove.add(a);
            }
        }

        if (!toRemove.isEmpty()) {
            assignments.removeAll(toRemove);
            schedule.setAssignmentList(assignments);
            log.warn("ASSIGNMENT removed {} slots with empty candidates (before={}, after={}).", toRemove.size(), originalCount, assignments.size());
        } else {
            schedule.setAssignmentList(assignments);
        }
    }

    private boolean matchesAnyPattern(List<EmployeeShiftPattern> list,
                                      LocalDate date, LocalTime slotStart, LocalTime slotEnd) {
        if (list == null || list.isEmpty()) return true;
        for (var p : list) {
            if (Boolean.FALSE.equals(p.getActive())) continue;
            var ps = p.getStartTime().toLocalTime();
            var pe = p.getEndTime().toLocalTime();
            if ((slotStart.equals(ps) || slotStart.isAfter(ps)) && (slotEnd.isBefore(pe) || slotEnd.equals(pe))) {
                return true;
            }
        }
        return false;
    }

    private boolean withinWeeklyBase(Map<Integer, EmployeeWeeklyPreference> prefByDow,
                                     LocalDate date, LocalTime slotStart, LocalTime slotEnd) {
        if (prefByDow == null) return true;
        var pref = prefByDow.get(date.getDayOfWeek().getValue());
        if (pref == null) return true;
        if ("OFF".equalsIgnoreCase(pref.getWorkStyle())) return false;
        if (pref.getBaseStartTime() == null || pref.getBaseEndTime() == null) return true;
        var bs = pref.getBaseStartTime().toLocalTime();
        var be = pref.getBaseEndTime().toLocalTime();
        return (slotStart.equals(bs) || slotStart.isAfter(bs)) && (slotEnd.isBefore(be) || slotEnd.equals(be));
    }

    private boolean wouldExceedConsecutiveCap(Set<LocalDate> attendanceDays, LocalDate date, int cap) {
        if (attendanceDays == null || attendanceDays.isEmpty()) return false;
        for (int i = 1; i <= cap; i++) {
            LocalDate d = date.minusDays(i);
            if (!attendanceDays.contains(d)) {
                return false;
            }
        }
        return true;
    }
}
