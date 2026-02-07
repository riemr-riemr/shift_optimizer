package io.github.riemr.shift.application.service;

import io.github.riemr.shift.application.dto.EmployeeMonthlyShiftDto;
import io.github.riemr.shift.application.dto.EmployeeShiftDetailDto;
import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.infrastructure.mapper.ShiftAssignmentMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.ShiftAssignment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 従業員個人シフト表示用サービス
 */
@Service
@RequiredArgsConstructor
public class EmployeeShiftService {

    private final EmployeeMapper employeeMapper;
    private final ShiftAssignmentMapper shiftAssignmentMapper;

    /**
     * 指定した従業員の月間シフトを取得
     */
    public EmployeeMonthlyShiftDto getEmployeeMonthlyShift(String employeeCode, YearMonth targetMonth) {
        // 従業員情報を取得
        Employee employee = employeeMapper.selectByPrimaryKey(employeeCode);
        if (employee == null) {
            throw new IllegalArgumentException("指定された従業員が見つかりません: " + employeeCode);
        }

        // 対象月の全てのシフト割り当てを取得
        LocalDate startDate = targetMonth.atDay(1);
        LocalDate endDate = targetMonth.atEndOfMonth();
        List<ShiftAssignment> assignments = getShiftAssignmentsForMonth(employeeCode, startDate, endDate);

        // 日別シフト詳細のマップを作成
        Map<LocalDate, EmployeeShiftDetailDto> dailyShifts = createDailyShiftsMap(assignments, startDate, endDate);

        // カレンダー表示用の週リストを作成
        List<List<LocalDate>> weeks = createWeeksList(targetMonth);

        // 月間統計情報を計算
        EmployeeMonthlyShiftDto.MonthlyStats stats = calculateMonthlyStats(dailyShifts);

        return EmployeeMonthlyShiftDto.builder()
                .employeeCode(employee.getEmployeeCode())
                .employeeName(employee.getEmployeeName())
                .storeCode(employee.getStoreCode())
                .targetMonth(targetMonth)
                .dailyShifts(dailyShifts)
                .weeks(weeks)
                .monthlyStats(stats)
                .build();
    }

    /**
     * 指定期間の従業員のシフト割り当てを取得
     */
    private List<ShiftAssignment> getShiftAssignmentsForMonth(String employeeCode, LocalDate startDate, LocalDate endDate) {
        List<ShiftAssignment> allAssignments = shiftAssignmentMapper.selectAll();
        return allAssignments.stream()
                .filter(assignment -> employeeCode.equals(assignment.getEmployeeCode()))
                .filter(assignment -> {
                    LocalDate assignmentDate = assignment.getStartAt().toInstant()
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate();
                    return !assignmentDate.isBefore(startDate) && !assignmentDate.isAfter(endDate);
                })
                .collect(Collectors.toList());
    }

    /**
     * 日別シフト詳細のマップを作成
     */
    private Map<LocalDate, EmployeeShiftDetailDto> createDailyShiftsMap(
            List<ShiftAssignment> assignments, LocalDate startDate, LocalDate endDate) {
        
        Map<LocalDate, EmployeeShiftDetailDto> dailyShifts = new LinkedHashMap<>();
        
        // 全日付を初期化（休日として）
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            dailyShifts.put(current, EmployeeShiftDetailDto.builder()
                    .workDate(current)
                    .isHoliday(true)
                    .workMinutes(0)
                    .build());
            current = current.plusDays(1);
        }

        // シフト割り当てがある日付を更新
        Map<LocalDate, List<ShiftAssignment>> assignmentsByDate = assignments.stream()
                .collect(Collectors.groupingBy(assignment -> 
                    assignment.getStartAt().toInstant()
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()));

        assignmentsByDate.forEach((date, dayAssignments) -> {
            // 同日の全ての割り当てを統合（開始時刻が最も早い、終了時刻が最も遅い）
            LocalDateTime earliestStart = dayAssignments.stream()
                    .map(ShiftAssignment::getStartAt)
                    .map(timestamp -> timestamp.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime())
                    .min(LocalDateTime::compareTo)
                    .orElse(null);
            
            LocalDateTime latestEnd = dayAssignments.stream()
                    .map(ShiftAssignment::getEndAt)
                    .map(timestamp -> timestamp.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime())
                    .max(LocalDateTime::compareTo)
                    .orElse(null);

            if (earliestStart != null && latestEnd != null) {
                int workMinutes = (int) java.time.Duration.between(earliestStart, latestEnd).toMinutes();
                
                // 最初の割り当てから店舗コードを取得
                ShiftAssignment firstAssignment = dayAssignments.get(0);
                
                dailyShifts.put(date, EmployeeShiftDetailDto.builder()
                        .workDate(date)
                        .startTime(earliestStart.toLocalTime())
                        .endTime(latestEnd.toLocalTime())
                        .workMinutes(workMinutes)
                        .registerNo(null) // ShiftAssignmentにはregisterNoフィールドがないため、nullに設定
                        .storeCode(firstAssignment.getStoreCode())
                        .isHoliday(false)
                        .build());
            }
        });

        return dailyShifts;
    }

    /**
     * カレンダー表示用の週リストを作成
     */
    private List<List<LocalDate>> createWeeksList(YearMonth targetMonth) {
        List<List<LocalDate>> weeks = new ArrayList<>();
        LocalDate firstDayOfMonth = targetMonth.atDay(1);
        int firstDayOfWeek = firstDayOfMonth.getDayOfWeek().getValue() % 7; // 日曜日を0とする
        LocalDate currentDate = firstDayOfMonth.minusDays(firstDayOfWeek);

        for (int i = 0; i < 6; i++) {
            List<LocalDate> week = new ArrayList<>();
            for (int j = 0; j < 7; j++) {
                week.add(currentDate);
                currentDate = currentDate.plusDays(1);
            }
            weeks.add(week);
            if (currentDate.getMonth() != targetMonth.getMonth() && i >= 3) {
                break;
            }
        }

        return weeks;
    }

    /**
     * 月間統計情報を計算
     */
    private EmployeeMonthlyShiftDto.MonthlyStats calculateMonthlyStats(Map<LocalDate, EmployeeShiftDetailDto> dailyShifts) {
        List<EmployeeShiftDetailDto> workDays = dailyShifts.values().stream()
                .filter(shift -> !shift.isHoliday())
                .collect(Collectors.toList());

        int totalWorkDays = workDays.size();
        int totalWorkMinutes = workDays.stream()
                .mapToInt(EmployeeShiftDetailDto::getWorkMinutes)
                .sum();
        
        int averageWorkMinutesPerDay = totalWorkDays > 0 ? totalWorkMinutes / totalWorkDays : 0;
        
        int maxWorkMinutes = workDays.stream()
                .mapToInt(EmployeeShiftDetailDto::getWorkMinutes)
                .max()
                .orElse(0);
        
        int minWorkMinutes = workDays.stream()
                .mapToInt(EmployeeShiftDetailDto::getWorkMinutes)
                .filter(minutes -> minutes > 0)
                .min()
                .orElse(0);

        int holidayCount = (int) dailyShifts.values().stream()
                .filter(EmployeeShiftDetailDto::isHoliday)
                .count();

        return EmployeeMonthlyShiftDto.MonthlyStats.builder()
                .totalWorkDays(totalWorkDays)
                .totalWorkMinutes(totalWorkMinutes)
                .averageWorkMinutesPerDay(averageWorkMinutesPerDay)
                .maxWorkMinutes(maxWorkMinutes)
                .minWorkMinutes(minWorkMinutes)
                .holidayCount(holidayCount)
                .build();
    }

    /**
     * 全従業員のリストを取得
     */
    public List<Employee> getAllEmployees() {
        return employeeMapper.selectAll();
    }
}