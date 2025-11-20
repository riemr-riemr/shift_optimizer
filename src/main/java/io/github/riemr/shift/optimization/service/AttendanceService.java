package io.github.riemr.shift.optimization.service;

import io.github.riemr.shift.application.repository.ShiftScheduleRepository;
import io.github.riemr.shift.infrastructure.mapper.ShiftAssignmentMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRequest;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeShiftPattern;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterDemandQuarter;
import io.github.riemr.shift.infrastructure.persistence.entity.ShiftAssignment;
import io.github.riemr.shift.optimization.entity.DailyPatternAssignmentEntity;
import io.github.riemr.shift.optimization.solution.AttendanceSolution;
import io.github.riemr.shift.optimization.solution.ShiftSchedule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private final ShiftScheduleRepository repository;
    private final ShiftAssignmentMapper shiftAssignmentMapper;

    public AttendanceSolution loadAttendanceProblem(ProblemKey key) {
        log.error("=== LOAD ATTENDANCE PROBLEM STARTED ===");
        LocalDate cycleStart = key.getCycleStart();
        ShiftSchedule base = repository.fetchShiftSchedule(cycleStart, key.getStoreCode(), key.getDepartmentCode());
        AttendanceSolution sol = new AttendanceSolution();
        sol.setProblemId(toProblemId(cycleStart));
        sol.setMonth(cycleStart);
        sol.setStoreCode(key.getStoreCode());
        sol.setDepartmentCode(key.getDepartmentCode());
        sol.setEmployeeList(base.getEmployeeList());
        sol.setEmployeeShiftPatternList(base.getEmployeeShiftPatternList());
        sol.setEmployeeWeeklyPreferenceList(base.getEmployeeWeeklyPreferenceList());
        sol.setEmployeeRequestList(base.getEmployeeRequestList());
        sol.setEmployeeMonthlySettingList(base.getEmployeeMonthlySettingList());
        sol.setDemandList(base.getDemandList());
        var patterns = buildPatternAssignmentsFromDemand(sol);
        sol.setPatternAssignments(patterns);

        long assignedCount = patterns.stream().filter(p -> p.getAssignedEmployee() != null).count();
        long unassignedCount = patterns.size() - assignedCount;
        log.info("Generated {} pattern assignments for ATTENDANCE optimization (assigned: {}, unassigned: {})",
                patterns.size(), assignedCount, unassignedCount);

        var demand = Optional.ofNullable(sol.getDemandList()).orElse(List.of());
        int totalDemandUnits = demand.stream().mapToInt(RegisterDemandQuarter::getRequiredUnits).sum();
        log.info("Total demand units: {}, Total pattern slots: {}, Coverage: {:.1f}%",
                totalDemandUnits, patterns.size(), (double) patterns.size() / Math.max(1, totalDemandUnits) * 100);

        return sol;
    }

    @Transactional
    public void persistAttendanceResult(AttendanceSolution best, ProblemKey key) {
        if (best == null || best.getPatternAssignments() == null) {
            log.warn("Cannot persist attendance result - solution or pattern assignments are null");
            return;
        }
        long assignedPatterns = best.getPatternAssignments().stream().filter(p -> p.getAssignedEmployee() != null).count();
        log.info("Persisting attendance result: score={}, total_patterns={}, assigned_patterns={}",
                best.getScore(), best.getPatternAssignments().size(), assignedPatterns);

        LocalDate from = best.getMonth();
        LocalDate to = from.plusMonths(1);
        String store = best.getStoreCode();
        if (store == null || store.isBlank()) throw new IllegalStateException("storeCode must not be null for attendance persist");
        int del = shiftAssignmentMapper.deleteByMonthAndStore(from, to, store);
        log.info("[attendance] Cleared shift_assignment rows: {} for store={}, from={}, to={}", del, store, from, to);

        ZoneId zone = ZoneId.systemDefault();
        int ins = 0;
        Set<String> dedup = new HashSet<>();
        for (var e : best.getPatternAssignments()) {
            if (e.getAssignedEmployee() == null) continue;
            var sa = new ShiftAssignment();
            sa.setStoreCode(store);
            sa.setEmployeeCode(e.getAssignedEmployee().getEmployeeCode());
            var startAt = Date.from(e.getDate().atTime(e.getPatternStart()).atZone(zone).toInstant());
            var endAt = Date.from(e.getDate().atTime(e.getPatternEnd()).atZone(zone).toInstant());
            sa.setStartAt(startAt);
            sa.setEndAt(endAt);
            sa.setCreatedBy("auto");
            String k = store + "|" + sa.getEmployeeCode() + "|" + sa.getStartAt().getTime();
            if (dedup.add(k)) {
                shiftAssignmentMapper.upsert(sa);
                ins++;
            }
        }
        log.info("[attendance] Persisted rows: {} (from {} assigned patterns)", ins, assignedPatterns);
    }

    // ===== ATTENDANCE pattern/candidate building =====
    private List<DailyPatternAssignmentEntity> buildPatternAssignmentsFromDemand(AttendanceSolution sol) {
        List<DailyPatternAssignmentEntity> result = new ArrayList<>();
        var patterns = Optional.ofNullable(sol.getEmployeeShiftPatternList()).orElse(List.of());
        var demand = Optional.ofNullable(sol.getDemandList()).orElse(List.of());
        var employees = Optional.ofNullable(sol.getEmployeeList()).orElse(List.of());
        var weeklyPrefs = Optional.ofNullable(sol.getEmployeeWeeklyPreferenceList()).orElse(List.of());
        var requests = Optional.ofNullable(sol.getEmployeeRequestList()).orElse(List.of());
        if (patterns.isEmpty()) return result;
        ZoneId zone = ZoneId.systemDefault();

        Map<LocalDate, List<RegisterDemandQuarter>> demandByDate = new HashMap<>();
        for (var d : demand) {
            Date dd = d.getDemandDate();
            LocalDate dt = (dd instanceof java.sql.Date)
                    ? ((java.sql.Date) dd).toLocalDate()
                    : dd.toInstant().atZone(zone).toLocalDate();
            demandByDate.computeIfAbsent(dt, k -> new java.util.ArrayList<>()).add(d);
        }

        LinkedHashSet<String> windowKeys = new LinkedHashSet<>();
        List<LocalTime[]> windows = new ArrayList<>();
        for (var p : patterns) {
            if (Boolean.FALSE.equals(p.getActive())) continue;
            Short prio = p.getPriority();
            if (prio == null || prio.intValue() < 2) continue;
            var ps = p.getStartTime().toLocalTime();
            var pe = p.getEndTime().toLocalTime();
            String key = ps + "_" + pe;
            if (windowKeys.add(key)) {
                windows.add(new LocalTime[]{ps, pe});
            }
        }

        var cycleStart = sol.getMonth();
        var cycleEnd = cycleStart.plusMonths(1);
        // 既存ロスター（当月）の出勤日集合を取得し、7連勤抑止
        Map<String, Set<LocalDate>> attendanceDaysByEmp = new HashMap<>();
        final int maxConsecutiveDays = 6;
        List<ShiftAssignment> attendance = shiftAssignmentMapper.selectByMonth(cycleStart, cycleEnd);
        if (sol.getStoreCode() != null) {
            attendance = attendance.stream().filter(sa -> sol.getStoreCode().equals(sa.getStoreCode())).toList();
        }
        for (var sa : attendance) {
            if (sa.getEmployeeCode() == null || sa.getStartAt() == null) continue;
            LocalDate d = sa.getStartAt().toInstant().atZone(zone).toLocalDate();
            attendanceDaysByEmp.computeIfAbsent(sa.getEmployeeCode(), k -> new HashSet<>()).add(d);
        }

        for (var date = cycleStart; date.isBefore(cycleEnd); date = date.plusDays(1)) {
            var quarters = demandByDate.getOrDefault(date, List.of());
            for (var win : windows) {
                var ps = win[0];
                var pe = win[1];
                int maxUnits = 0;
                for (var q : quarters) {
                    var qt = q.getSlotTime();
                    if ((qt.equals(ps) || qt.isAfter(ps)) && qt.isBefore(pe)) {
                        maxUnits = Math.max(maxUnits, Optional.ofNullable(q.getRequiredUnits()).orElse(0));
                    }
                }
                for (int i = 0; i < maxUnits; i++) {
                    String id = sol.getStoreCode() + "|" + sol.getDepartmentCode() + "|" + date + "|" + ps + "|" + pe + "|" + i;
                    var ent = new DailyPatternAssignmentEntity(id, sol.getStoreCode(), sol.getDepartmentCode(), date, ps, pe, i);
                    var candidates = computeEligibleEmployeesForWindow(employees, patterns, weeklyPrefs, requests,
                            attendanceDaysByEmp, maxConsecutiveDays, date, ps, pe);
                    ent.setCandidateEmployees(candidates);
                    result.add(ent);
                }
            }
        }
        return result;
    }

    private List<Employee> computeEligibleEmployeesForWindow(
            List<Employee> employees,
            List<EmployeeShiftPattern> patterns,
            List<EmployeeWeeklyPreference> weeklyPrefs,
            List<EmployeeRequest> requests,
            Map<String, Set<LocalDate>> attendanceDaysByEmp,
            int maxConsecutiveDays,
            LocalDate date,
            LocalTime ps,
            LocalTime pe) {
        Map<String, List<EmployeeShiftPattern>> pattByEmp = patterns.stream().collect(Collectors.groupingBy(EmployeeShiftPattern::getEmployeeCode));
        Map<String, List<EmployeeWeeklyPreference>> weeklyByEmp = weeklyPrefs.stream().collect(Collectors.groupingBy(EmployeeWeeklyPreference::getEmployeeCode));
        Map<String, Set<LocalDate>> offByEmp = new HashMap<>();
        for (var r : requests) {
            if (r.getRequestKind() == null) continue;
            String kind = r.getRequestKind().toLowerCase();
            if ("off".equals(kind) || "paid_leave".equals(kind)) {
                LocalDate d = (r.getRequestDate() instanceof java.sql.Date)
                        ? ((java.sql.Date) r.getRequestDate()).toLocalDate()
                        : r.getRequestDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                offByEmp.computeIfAbsent(r.getEmployeeCode(), k -> new HashSet<>()).add(d);
            }
        }
        int dow = date.getDayOfWeek().getValue();
        List<Employee> list = new ArrayList<>();
        for (var e : employees) {
            String code = e.getEmployeeCode();
            boolean hasPattern = pattByEmp.getOrDefault(code, List.of()).stream()
                    .anyMatch(p -> !Boolean.FALSE.equals(p.getActive())
                            && p.getPriority() != null && p.getPriority().intValue() >= 2
                            && p.getStartTime().toLocalTime().equals(ps)
                            && p.getEndTime().toLocalTime().equals(pe));
            if (!hasPattern) continue;
            if (offByEmp.getOrDefault(code, Set.of()).contains(date)) continue;
            boolean weeklyOk = true;
            for (var w : weeklyByEmp.getOrDefault(code, List.of())) {
                if (w.getDayOfWeek() == null || w.getDayOfWeek().intValue() != dow) continue;
                if ("OFF".equalsIgnoreCase(w.getWorkStyle())) { weeklyOk = false; break; }
                if (w.getBaseStartTime() != null && w.getBaseEndTime() != null) {
                    var bs = w.getBaseStartTime().toLocalTime();
                    var be = w.getBaseEndTime().toLocalTime();
                    if (ps.isBefore(bs) || pe.isAfter(be)) { weeklyOk = false; break; }
                }
            }
            if (!weeklyOk) continue;
            var attDays = attendanceDaysByEmp.getOrDefault(code, Set.of());
            if (wouldExceedConsecutiveCap(attDays, date, maxConsecutiveDays)) continue;
            list.add(e);
        }
        return list;
    }

    // ====== ShiftSchedule 上の候補作成（必要に応じて利用） ======
    public void prepareCandidateEmployeesForAttendance(ShiftSchedule schedule, LocalDate cycleStart) {
        // 再利用: ShiftScheduleService から抽出済みのロジックをこちらにも提供
        final var employees = schedule.getEmployeeList();
        final var requests = Optional.ofNullable(schedule.getEmployeeRequestList()).orElse(List.of());
        final var weekly = Optional.ofNullable(schedule.getEmployeeWeeklyPreferenceList()).orElse(List.of());

        Map<String, Set<LocalDate>> offDatesByEmp = new HashMap<>();
        for (var r : requests) {
            if (r.getRequestKind() == null) continue;
            String kind = r.getRequestKind().toLowerCase();
            if ("off".equals(kind) || "paid_leave".equals(kind)) {
                LocalDate d = r.getRequestDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                offDatesByEmp.computeIfAbsent(r.getEmployeeCode(), k -> new HashSet<>()).add(d);
            }
        }
        Map<String, Set<Integer>> weeklyOffByEmp = new HashMap<>();
        Map<String, Map<Integer, EmployeeWeeklyPreference>> weeklyPrefByEmpDow = new HashMap<>();
        for (var p : weekly) {
            weeklyPrefByEmpDow.computeIfAbsent(p.getEmployeeCode(), k -> new HashMap<>())
                    .put(p.getDayOfWeek().intValue(), p);
            if ("OFF".equalsIgnoreCase(p.getWorkStyle()))
                weeklyOffByEmp.computeIfAbsent(p.getEmployeeCode(), k -> new HashSet<>()).add(p.getDayOfWeek().intValue());
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
            attendanceDaysByEmp.computeIfAbsent(sa.getEmployeeCode(), k -> new HashSet<>()).add(d);
        }

        Map<String, List<EmployeeShiftPattern>> patternByEmp = Optional.ofNullable(schedule.getEmployeeShiftPatternList()).orElse(List.of())
                .stream().collect(Collectors.groupingBy(EmployeeShiftPattern::getEmployeeCode));

        for (var a : schedule.getAssignmentList()) {
            LocalDate date = a.getShiftDate();
            List<Employee> cands = employees.stream().filter(e -> {
                var offSet = offDatesByEmp.getOrDefault(e.getEmployeeCode(), Set.of());
                if (offSet.contains(date)) return false;
                var offDow = weeklyOffByEmp.getOrDefault(e.getEmployeeCode(), Set.of());
                if (offDow.contains(date.getDayOfWeek().getValue())) return false;
                if (!withinWeeklyBase(weeklyPrefByEmpDow.get(e.getEmployeeCode()), date,
                        a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime(),
                        a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime())) return false;
                if (!matchesAnyPattern(patternByEmp.get(e.getEmployeeCode()), date,
                        a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime(),
                        a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime())) return false;
                var attDays = attendanceDaysByEmp.getOrDefault(e.getEmployeeCode(), Set.of());
                if (wouldExceedConsecutiveCap(attDays, date, maxConsecutiveDays)) return false;
                return true;
            }).toList();
            a.setCandidateEmployees(cands);
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

    private static long toProblemId(LocalDate month) {
        return month.getYear() * 100L + month.getMonthValue();
    }
}
