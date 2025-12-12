package io.github.riemr.shift.optimization.phase;

import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeDepartmentSkill;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRegisterSkill;
import io.github.riemr.shift.infrastructure.persistence.entity.Register;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import io.github.riemr.shift.optimization.entity.WorkKind;
import io.github.riemr.shift.optimization.solution.ShiftSchedule;
import org.optaplanner.core.api.score.director.ScoreDirector;
import org.optaplanner.core.impl.phase.custom.CustomPhaseCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ASSIGNMENT フェーズのカスタム初期解生成。
 * レジの優先度（is_auto_open_target, open_priority, register_no）順で、
 * スキルの高い（0/1は候補外）かつその時間に未割当の従業員へ割当。
 * 初期解では、各レジ（および部門作業）の最大割当時間（max_allowance 等）をブロック長として、
 * 連続スロットをまとめて割り当てる。
 */
public class AssignmentInitialSolutionBuilder implements CustomPhaseCommand<ShiftSchedule> {

    private static final Logger log = LoggerFactory.getLogger(AssignmentInitialSolutionBuilder.class);

    @Override
    public void changeWorkingSolution(ScoreDirector<ShiftSchedule> scoreDirector) {
        ShiftSchedule sol = scoreDirector.getWorkingSolution();
        if (sol == null || sol.getAssignmentList() == null || sol.getAssignmentList().isEmpty()) {
            return;
        }
        // ASSIGNMENT フェーズ以外では何もしない
        // stage は各エンティティに設定済み想定
        boolean isAssignment = sol.getAssignmentList().stream().anyMatch(a -> "ASSIGNMENT".equals(a.getStage()));
        if (!isAssignment) {
            return;
        }

        // スロット分解能（分）を推定（最初のエンティティから）
        int slotMinutes = 15;
        for (var a : sol.getAssignmentList()) {
            if (a.getStartAt() != null && a.getEndAt() != null) {
                slotMinutes = (int) Duration.between(a.getStartAt().toInstant(), a.getEndAt().toInstant()).toMinutes();
                break;
            }
        }

        // レジ優先順（store単位）
        Map<String, List<Register>> registerByStore = Optional.ofNullable(sol.getRegisterList()).orElse(List.of())
                .stream().collect(Collectors.groupingBy(Register::getStoreCode));
        Map<String, List<Register>> sortedRegisters = new HashMap<>();
        for (var e : registerByStore.entrySet()) {
            Comparator<Register> comp = Comparator
                    .comparing(Register::getIsAutoOpenTarget, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Register::getOpenPriority, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Register::getRegisterNo);
            sortedRegisters.put(e.getKey(), e.getValue().stream().sorted(comp).toList());
        }

        // スキルインデックス
        Map<String, Map<Integer, Short>> regSkillByEmp = new HashMap<>();
        for (EmployeeRegisterSkill s : Optional.ofNullable(sol.getEmployeeRegisterSkillList()).orElse(List.of())) {
            regSkillByEmp.computeIfAbsent(s.getEmployeeCode(), k -> new HashMap<>()).put(s.getRegisterNo(), s.getSkillLevel());
        }
        Map<String, Map<String, Short>> deptSkillByEmp = new HashMap<>();
        for (EmployeeDepartmentSkill s : Optional.ofNullable(sol.getEmployeeDepartmentSkillList()).orElse(List.of())) {
            deptSkillByEmp.computeIfAbsent(s.getEmployeeCode(), k -> new HashMap<>()).put(s.getDepartmentCode(), s.getSkillLevel());
        }

        // レジの max_allowance（分）
        Map<String, Map<Integer, Integer>> maxAllowanceByStoreReg = new HashMap<>();
        for (Register r : Optional.ofNullable(sol.getRegisterList()).orElse(List.of())) {
            int minutes = (r.getMaxAllowance() == null ? 60 : r.getMaxAllowance() * 60); // null は 60 分既定
            maxAllowanceByStoreReg.computeIfAbsent(r.getStoreCode(), k -> new HashMap<>()).put(r.getRegisterNo(), minutes);
        }

        // 日付 → エンティティ群のインデックス
        Map<LocalDate, List<ShiftAssignmentPlanningEntity>> byDate = sol.getAssignmentList().stream()
                .collect(Collectors.groupingBy(ShiftAssignmentPlanningEntity::getShiftDate));

        // 従業員の当日割当インターバル管理（ダブルブッキング回避）
        Map<String, Map<LocalDate, List<Interval>>> assignedIntervals = new HashMap<>();

        int totalAssigned = 0;

        for (var entry : byDate.entrySet()) {
            LocalDate date = entry.getKey();
            List<ShiftAssignmentPlanningEntity> dayList = entry.getValue();

            // レジ: 優先順に処理
            Map<Integer, List<ShiftAssignmentPlanningEntity>> byRegister = dayList.stream()
                    .filter(a -> a.getWorkKind() == WorkKind.REGISTER_OP)
                    .filter(a -> a.getRegisterNo() != null)
                    .collect(Collectors.groupingBy(ShiftAssignmentPlanningEntity::getRegisterNo));

            List<Register> regs = sortedRegisters.getOrDefault(sol.getStoreCode(), List.of());
            // store 指定がない場合、キーは origin.storeCode を使って取り直す
            if (sol.getStoreCode() == null && !dayList.isEmpty()) {
                String store = dayList.get(0).getStoreCode();
                regs = sortedRegisters.getOrDefault(store, regs);
            }

            for (Register reg : regs) {
                List<ShiftAssignmentPlanningEntity> slots = byRegister.getOrDefault(reg.getRegisterNo(), List.of())
                        .stream().sorted(Comparator.comparing(ShiftAssignmentPlanningEntity::getStartAt)).toList();
                if (slots.isEmpty()) continue;
                int blockMaxMinutes = maxAllowanceByStoreReg
                        .getOrDefault(slots.get(0).getStoreCode(), Map.of())
                        .getOrDefault(reg.getRegisterNo(), 60);

                int i = 0;
                while (i < slots.size()) {
                    // 連続ブロックの終端を決める（max_allowance 分まで）
                    int acc = 0; int j = i;
                    while (j < slots.size()) {
                        int m = (int) Duration.between(slots.get(j).getStartAt().toInstant(), slots.get(j).getEndAt().toInstant()).toMinutes();
                        if (acc + m > blockMaxMinutes) break;
                        // 連続確認（隙間無し）
                        if (j > i) {
                            var prev = slots.get(j - 1);
                            if (!prev.getEndAt().toInstant().equals(slots.get(j).getStartAt().toInstant())) break;
                        }
                        acc += m; j++;
                    }
                    if (j == i) { j = i + 1; acc = slotMinutes; }

                    // 候補から最適従業員を選択（スキル降順、負荷軽い順）
                    List<Employee> cands = slots.get(i).getAvailableEmployees();
                    if (cands == null || cands.isEmpty()) { i = j; continue; }
                    List<Employee> ranked = cands.stream()
                            .sorted(Comparator
                                    .comparing((Employee e) -> skillForRegister(regSkillByEmp, e.getEmployeeCode(), reg.getRegisterNo())).reversed()
                                    .thenComparing(e -> totalMinutesAssigned(assignedIntervals, e.getEmployeeCode(), date)))
                            .toList();

                    Employee chosen = null;
                    for (Employee e : ranked) {
                        Short lv = skillForRegister(regSkillByEmp, e.getEmployeeCode(), reg.getRegisterNo());
                        if (lv == null || lv <= 1) continue; // 0/1 は不可
                        if (!overlaps(assignedIntervals, e.getEmployeeCode(), date,
                                slots.get(i).getStartAt(), slots.get(j - 1).getEndAt())) {
                            chosen = e; break;
                        }
                    }
                    if (chosen != null) {
                        // ブロック割当
                        assignBlock(scoreDirector, chosen, slots.subList(i, j));
                        addInterval(assignedIntervals, chosen.getEmployeeCode(), date,
                                slots.get(i).getStartAt(), slots.get(j - 1).getEndAt());
                        totalAssigned += (j - i);
                    }
                    i = j;
                }
            }

            // 部門タスク: 部門スキルを優先（簡易実装、ブロック長は 60 分）
            Map<String, List<ShiftAssignmentPlanningEntity>> byTask = dayList.stream()
                    .filter(a -> a.getWorkKind() == WorkKind.DEPARTMENT_TASK)
                    .collect(Collectors.groupingBy(a -> a.getTaskCode() == null ? "" : a.getTaskCode()));

            for (var tEntry : byTask.entrySet()) {
                List<ShiftAssignmentPlanningEntity> slots = tEntry.getValue().stream()
                        .sorted(Comparator.comparing(ShiftAssignmentPlanningEntity::getStartAt)).toList();
                if (slots.isEmpty()) continue;

                int blockMaxMinutes = 60; // タスクは既定 60 分ブロック（要件に応じて拡張）
                int i = 0;
                while (i < slots.size()) {
                    int acc = 0; int j = i;
                    while (j < slots.size()) {
                        int m = (int) Duration.between(slots.get(j).getStartAt().toInstant(), slots.get(j).getEndAt().toInstant()).toMinutes();
                        if (acc + m > blockMaxMinutes) break;
                        if (j > i) {
                            var prev = slots.get(j - 1);
                            if (!prev.getEndAt().toInstant().equals(slots.get(j).getStartAt().toInstant())) break;
                        }
                        acc += m; j++;
                    }
                    if (j == i) { j = i + 1; acc = slotMinutes; }

                    List<Employee> cands = slots.get(i).getAvailableEmployees();
                    if (cands == null || cands.isEmpty()) { i = j; continue; }
                    // 部門スキル降順、負荷軽い順
                    String dept = slots.get(i).getDepartmentCode();
                    List<Employee> ranked = cands.stream()
                            .sorted(Comparator
                                    .comparing((Employee e) -> skillForDepartment(deptSkillByEmp, e.getEmployeeCode(), dept)).reversed()
                                    .thenComparing(e -> totalMinutesAssigned(assignedIntervals, e.getEmployeeCode(), date)))
                            .toList();

                    Employee chosen = null;
                    for (Employee e : ranked) {
                        Short lv = skillForDepartment(deptSkillByEmp, e.getEmployeeCode(), dept);
                        if (lv == null || lv <= 1) continue;
                        if (!overlaps(assignedIntervals, e.getEmployeeCode(), date,
                                slots.get(i).getStartAt(), slots.get(j - 1).getEndAt())) {
                            chosen = e; break;
                        }
                    }
                    if (chosen != null) {
                        assignBlock(scoreDirector, chosen, slots.subList(i, j));
                        addInterval(assignedIntervals, chosen.getEmployeeCode(), date,
                                slots.get(i).getStartAt(), slots.get(j - 1).getEndAt());
                        totalAssigned += (j - i);
                    }
                    i = j;
                }
            }
        }

        scoreDirector.triggerVariableListeners();
        log.info("ASSIGNMENT Initial solution: assigned {} slots", totalAssigned);
    }

    private Short skillForRegister(Map<String, Map<Integer, Short>> map, String empCode, Integer registerNo) {
        if (empCode == null || registerNo == null) return 0;
        return map.getOrDefault(empCode, Map.of()).getOrDefault(registerNo, (short) 0);
    }

    private Short skillForDepartment(Map<String, Map<String, Short>> map, String empCode, String dept) {
        if (empCode == null || dept == null) return 0;
        return map.getOrDefault(empCode, Map.of()).getOrDefault(dept, (short) 0);
    }

    private void assignBlock(ScoreDirector<ShiftSchedule> sd, Employee e, List<ShiftAssignmentPlanningEntity> block) {
        for (var slot : block) {
            if (slot.getAssignedEmployee() != null) continue;
            sd.beforeVariableChanged(slot, "assignedEmployee");
            slot.setAssignedEmployee(e);
            sd.afterVariableChanged(slot, "assignedEmployee");
        }
    }

    private static class Interval {
        final Date start; final Date end;
        Interval(Date s, Date e) { this.start = s; this.end = e; }
    }

    private void addInterval(Map<String, Map<LocalDate, List<Interval>>> map, String emp, LocalDate date, Date s, Date e) {
        map.computeIfAbsent(emp, k -> new HashMap<>())
                .computeIfAbsent(date, k -> new ArrayList<>())
                .add(new Interval(s, e));
    }

    private boolean overlaps(Map<String, Map<LocalDate, List<Interval>>> map, String emp, LocalDate date, Date s, Date e) {
        List<Interval> list = map.getOrDefault(emp, Map.of()).getOrDefault(date, List.of());
        var si = s.toInstant(); var ei = e.toInstant();
        for (Interval iv : list) {
            var is = iv.start.toInstant(); var ie = iv.end.toInstant();
            boolean ov = is.isBefore(ei) && ie.isAfter(si);
            if (ov) return true;
        }
        return false;
    }

    private int totalMinutesAssigned(Map<String, Map<LocalDate, List<Interval>>> map, String emp, LocalDate date) {
        List<Interval> list = map.getOrDefault(emp, Map.of()).getOrDefault(date, List.of());
        int sum = 0;
        for (Interval iv : list) {
            sum += (int) Duration.between(iv.start.toInstant(), iv.end.toInstant()).toMinutes();
        }
        return sum;
    }
}

