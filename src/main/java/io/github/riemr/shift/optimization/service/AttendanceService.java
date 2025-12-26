package io.github.riemr.shift.optimization.service;

import io.github.riemr.shift.application.repository.ShiftScheduleRepository;
import io.github.riemr.shift.infrastructure.mapper.ShiftAssignmentMapper;
import io.github.riemr.shift.infrastructure.mapper.AttendanceGroupConstraintMapper;
import io.github.riemr.shift.infrastructure.mapper.AttendanceGroupMemberMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRequest;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeShiftPattern;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference;
import io.github.riemr.shift.infrastructure.persistence.entity.ShiftAssignment;
import io.github.riemr.shift.infrastructure.persistence.entity.AttendanceGroupConstraint;
import io.github.riemr.shift.infrastructure.persistence.entity.AttendanceGroupMember;
import io.github.riemr.shift.optimization.entity.RegisterDemandSlot;
import io.github.riemr.shift.optimization.entity.DailyPatternAssignmentEntity;
import io.github.riemr.shift.optimization.entity.WorkDemandSlot;
import io.github.riemr.shift.optimization.entity.AttendanceGroupInfo;
import io.github.riemr.shift.optimization.entity.AttendanceGroupRuleType;
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
    private final AttendanceGroupConstraintMapper attendanceGroupConstraintMapper;
    private final AttendanceGroupMemberMapper attendanceGroupMemberMapper;

    public AttendanceSolution loadAttendanceProblem(ProblemKey key) {
        log.info("Starting attendance problem load: key={}", key);
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
        sol.setDemandList(aggregateRegisterDemand(base.getDemandList()));
        sol.setWorkDemandList(base.getWorkDemandList());
        sol.setAttendanceGroupInfos(loadAttendanceGroupInfos(key.getStoreCode(), key.getDepartmentCode()));
        sol.setActiveDates(buildActiveDates(sol.getDemandList(), sol.getWorkDemandList()));
        var patterns = buildPatternAssignmentsFromDemand(sol);
        sol.setPatternAssignments(patterns);

        long assignedCount = patterns.stream().filter(p -> p.getAssignedEmployee() != null).count();
        long unassignedCount = patterns.size() - assignedCount;
        log.info("Generated {} pattern assignments for ATTENDANCE optimization (assigned: {}, unassigned: {})",
                patterns.size(), assignedCount, unassignedCount);

        var demand = Optional.ofNullable(sol.getDemandList()).orElse(List.of());
        int totalDemandUnits = demand.stream()
                .map(RegisterDemandSlot::getRequiredUnits)
                .filter(Objects::nonNull)
                .mapToInt(i -> Math.max(0, i))
                .sum();
        double coverage = (double) patterns.size() / Math.max(1, totalDemandUnits) * 100.0;
        log.info("Total demand units: {}, Total pattern slots: {}, Coverage: {}%",
                totalDemandUnits, patterns.size(), String.format(Locale.ROOT, "%.1f", coverage));

        return sol;
    }

    private List<LocalDate> buildActiveDates(List<RegisterDemandSlot> registerDemands,
                                             List<WorkDemandSlot> workDemands) {
        Set<LocalDate> active = new TreeSet<>();
        if (registerDemands != null) {
            for (var d : registerDemands) {
                if (d.getDemandDate() != null) active.add(d.getDemandDate());
            }
        }
        if (workDemands != null) {
            for (var d : workDemands) {
                if (d.getDemandDate() != null) active.add(d.getDemandDate());
            }
        }
        return new ArrayList<>(active);
    }

    private List<AttendanceGroupInfo> loadAttendanceGroupInfos(String storeCode, String departmentCode) {
        if (storeCode == null || storeCode.isBlank()) {
            return List.of();
        }
        List<AttendanceGroupConstraint> constraints =
                attendanceGroupConstraintMapper.selectByStoreAndDepartment(storeCode, departmentCode);
        if (constraints.isEmpty()) {
            return List.of();
        }
        List<Long> ids = constraints.stream()
                .map(AttendanceGroupConstraint::getConstraintId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<AttendanceGroupMember> members = attendanceGroupMemberMapper.selectByConstraintIds(ids);
        Map<Long, Set<String>> membersByConstraint = new HashMap<>();
        for (var m : members) {
            if (m.getConstraintId() == null || m.getEmployeeCode() == null) continue;
            membersByConstraint
                    .computeIfAbsent(m.getConstraintId(), k -> new HashSet<>())
                    .add(m.getEmployeeCode());
        }

        List<AttendanceGroupInfo> infos = new ArrayList<>();
        for (var c : constraints) {
            Long id = c.getConstraintId();
            if (id == null) continue;
            AttendanceGroupRuleType ruleType = AttendanceGroupRuleType.fromCode(c.getRuleType());
            if (ruleType == null) {
                log.warn("Skipping attendance group constraint with unknown rule_type: id={}, ruleType={}",
                        id, c.getRuleType());
                continue;
            }
            Set<String> memberCodes = membersByConstraint.getOrDefault(id, Collections.emptySet());
            if (memberCodes.isEmpty()) {
                log.warn("Skipping attendance group constraint with no members: id={}", id);
                continue;
            }
            Integer minOnDuty = null;
            if (ruleType == AttendanceGroupRuleType.MIN_ON_DUTY) {
                minOnDuty = c.getMinOnDuty();
                if (minOnDuty == null) {
                    log.warn("Skipping MIN_ON_DUTY constraint with null min_on_duty: id={}", id);
                    continue;
                }
                int normalized = Math.max(1, Math.min(minOnDuty, memberCodes.size()));
                minOnDuty = normalized;
            }
            infos.add(new AttendanceGroupInfo(id, storeCode, departmentCode, ruleType, minOnDuty, memberCodes));
        }
        return infos;
    }

    private List<RegisterDemandSlot> aggregateRegisterDemand(List<RegisterDemandSlot> demandList) {
        if (demandList == null || demandList.isEmpty()) return List.of();
        Map<String, RegisterDemandSlot> agg = new HashMap<>();
        for (var d : demandList) {
            if (d.getDemandDate() == null || d.getSlotTime() == null) continue;
            String key = d.getStoreCode() + "|" + d.getDemandDate() + "|" + d.getSlotTime();
            int units = d.getRequiredUnits() == null ? 0 : Math.max(0, d.getRequiredUnits());
            agg.compute(key, (k, existing) -> {
                if (existing == null) {
                    RegisterDemandSlot slot = new RegisterDemandSlot();
                    slot.setStoreCode(d.getStoreCode());
                    slot.setDemandDate(d.getDemandDate());
                    slot.setSlotTime(d.getSlotTime());
                    slot.setRequiredUnits(units);
                    return slot;
                }
                int cur = existing.getRequiredUnits() == null ? 0 : existing.getRequiredUnits();
                existing.setRequiredUnits(cur + units);
                return existing;
            });
        }
        return new ArrayList<>(agg.values());
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
        if (patterns.isEmpty()) {
            log.warn("No employee shift patterns; attendance assignments cannot be generated (store={}, dept={}, month={})",
                    sol.getStoreCode(), sol.getDepartmentCode(), sol.getMonth());
            return result;
        }
        ZoneId zone = ZoneId.systemDefault();

        Map<LocalDate, Map<LocalTime, Integer>> demandByDate = new HashMap<>();
        long nullDemandDate = 0;
        for (var d : demand) {
            LocalDate dt = d.getDemandDate();
            if (dt == null) {
                nullDemandDate++;
                continue;
            }
            var byTime = demandByDate.computeIfAbsent(dt, k -> new java.util.HashMap<>());
            var time = d.getSlotTime();
            if (time == null) continue;
            int units = d.getRequiredUnits() == null ? 0 : Math.max(0, d.getRequiredUnits());
            byTime.merge(time, units, Integer::sum);
        }
        if (nullDemandDate > 0) {
            log.warn("Demand slots with null demandDate detected: {} (store={}, dept={}, month={})",
                    nullDemandDate, sol.getStoreCode(), sol.getDepartmentCode(), sol.getMonth());
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
        if (windows.isEmpty()) {
            long activeCount = patterns.stream().filter(p -> !Boolean.FALSE.equals(p.getActive())).count();
            log.warn("No active shift pattern windows with priority>=2; attendance assignments cannot be generated (patterns={}, active={}, store={}, dept={}, month={})",
                    patterns.size(), activeCount, sol.getStoreCode(), sol.getDepartmentCode(), sol.getMonth());
            return result;
        }

        var cycleStart = sol.getMonth();
        var cycleEnd = cycleStart.plusMonths(1);
        long demandInCycle = demand.stream()
                .map(RegisterDemandSlot::getDemandDate)
                .filter(Objects::nonNull)
                .filter(d -> !d.isBefore(cycleStart) && d.isBefore(cycleEnd))
                .count();
        if (!demand.isEmpty() && demandInCycle == 0) {
            Optional<LocalDate> min = demand.stream().map(RegisterDemandSlot::getDemandDate).filter(Objects::nonNull).min(LocalDate::compareTo);
            Optional<LocalDate> max = demand.stream().map(RegisterDemandSlot::getDemandDate).filter(Objects::nonNull).max(LocalDate::compareTo);
            log.warn("Demand slots exist but none fall within cycle [{},{}): total={}, inCycle=0, minDate={}, maxDate={}, store={}, dept={}",
                    cycleStart, cycleEnd, demand.size(), min.orElse(null), max.orElse(null), sol.getStoreCode(), sol.getDepartmentCode());
        }
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
            var quarters = demandByDate.getOrDefault(date, Map.of());
            for (var win : windows) {
                var ps = win[0];
                var pe = win[1];
                int maxUnits = 0;
                for (var entry : quarters.entrySet()) {
                    var qt = entry.getKey();
                    if ((qt.equals(ps) || qt.isAfter(ps)) && qt.isBefore(pe)) {
                        maxUnits = Math.max(maxUnits, entry.getValue());
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
