package io.github.riemr.shift.application.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.github.riemr.shift.application.dto.StaffingBalanceDto;
import io.github.riemr.shift.application.util.TimeIntervalQuarterUtils;
import io.github.riemr.shift.infrastructure.mapper.RegisterDemandIntervalMapper;
import io.github.riemr.shift.infrastructure.mapper.WorkDemandIntervalMapper;
import io.github.riemr.shift.infrastructure.mapper.ShiftAssignmentMapper;
import io.github.riemr.shift.infrastructure.mapper.DepartmentTaskAssignmentMapper;
import io.github.riemr.shift.infrastructure.mapper.StoreDepartmentMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.ShiftAssignment;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StaffingBalanceService {
    
    private final RegisterDemandIntervalMapper registerDemandIntervalMapper;
    private final WorkDemandIntervalMapper workDemandIntervalMapper;
    private final ShiftAssignmentMapper shiftMapper;
    private final DepartmentTaskAssignmentMapper departmentTaskAssignmentMapper;
    private final StoreDepartmentMapper storeDepartmentMapper;
    private final AppSettingService appSettingService;

    public List<StaffingBalanceDto> getStaffingBalance(String storeCode, LocalDate targetDate) {
        return getHourlyStaffingBalance(storeCode, targetDate);
    }

    public List<StaffingBalanceDto> getHourlyStaffingBalance(String storeCode, LocalDate targetDate) {
        return getHourlyStaffingBalance(storeCode, targetDate, null);
    }

    public List<StaffingBalanceDto> getHourlyStaffingBalance(String storeCode, LocalDate targetDate, String departmentCode) {
        boolean isRegister = (departmentCode == null || departmentCode.isBlank() || "520".equalsIgnoreCase(departmentCode));
        int resMin = appSettingService.getTimeResolutionMinutes();

        List<ShiftAssignment> assignments = new ArrayList<>();
        if (isRegister) {
            assignments = shiftMapper.selectByDate(targetDate, targetDate.plusDays(1));
        }

        Map<LocalTime, StaffingBalanceDto> balanceMap = new LinkedHashMap<>();

        LocalTime startTime = LocalTime.of(8, 0);
        // 8:00 - 23:00 のスロットを作る（終端は半開区間の最後の開始時刻）
        LocalTime endTime = LocalTime.of(23, 0).minusMinutes(resMin);
        LocalTime current = startTime;
        
        while (!current.isAfter(endTime)) {
            StaffingBalanceDto balance = StaffingBalanceDto.builder()
                    .storeCode(storeCode)
                    .targetDate(targetDate)
                    .slotTime(current)
                    .requiredStaff(0)
                    .assignedStaff(0)
                    .build();
            balanceMap.put(current, balance);
            current = current.plusMinutes(resMin);
        }

        if (isRegister) {
            var intervals = registerDemandIntervalMapper.selectByStoreAndDate(storeCode, targetDate);
            var quarters = TimeIntervalQuarterUtils.splitAll(intervals, resMin);
            for (var qs : quarters) {
                var b = balanceMap.get(qs.getStart());
                if (b != null) {
                    int cur = b.getRequiredStaff() == null ? 0 : b.getRequiredStaff();
                    b.setRequiredStaff(cur + (qs.getDemand() == null ? 0 : qs.getDemand()));
                }
            }
            List<String> departments = storeDepartmentMapper.findDepartmentsByStore(storeCode).stream()
                    .map(d -> d.getDepartmentCode())
                    .toList();
            addDepartmentTaskRequired(balanceMap, storeCode, targetDate, departments, resMin);
        }

        if (isRegister) {
            // Register assignments count as assigned staff
            for (ShiftAssignment assignment : assignments) {
                if (!storeCode.equals(assignment.getStoreCode())) continue;

                LocalDateTime startDateTime = assignment.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                LocalDateTime endDateTime = assignment.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                if (!startDateTime.toLocalDate().equals(targetDate) && !endDateTime.toLocalDate().equals(targetDate)) continue;

                LocalDateTime slotDateTime = LocalDateTime.of(targetDate, startTime);
                while (!slotDateTime.isAfter(LocalDateTime.of(targetDate, endTime))) {
                    if (!slotDateTime.isBefore(startDateTime) && slotDateTime.isBefore(endDateTime)) {
                        LocalTime slotTime = slotDateTime.toLocalTime();
                        StaffingBalanceDto balance = balanceMap.get(slotTime);
                        if (balance != null) balance.setAssignedStaff(balance.getAssignedStaff() + 1);
                    }
                    slotDateTime = slotDateTime.plusMinutes(resMin);
                }
            }
        } else {
            // Department workload path: required from work_demand_interval (sum across tasks)
            var workIntervals = workDemandIntervalMapper.selectByDate(storeCode, departmentCode, targetDate);
            var quarterSlots = TimeIntervalQuarterUtils.splitAll(workIntervals, resMin);
            // Sum across tasks for same quarter
            java.util.Map<LocalTime, Integer> reqBySlot = new java.util.HashMap<>();
            for (var qs : quarterSlots) {
                reqBySlot.merge(qs.getStart(), java.util.Objects.requireNonNullElse(qs.getDemand(), 0), Integer::sum);
            }
            for (var e : reqBySlot.entrySet()) {
                var b = balanceMap.get(e.getKey());
                if (b != null) b.setRequiredStaff(e.getValue());
            }
            addDepartmentTaskRequired(balanceMap, storeCode, targetDate, List.of(departmentCode), resMin);

            // Use month range-like query by date boundaries via selectByMonth or add a date method; we will scan by month proxy
            var dayAssignments = departmentTaskAssignmentMapper.selectByMonth(targetDate, targetDate.plusDays(1), storeCode, departmentCode);
            for (var a : dayAssignments) {
                LocalDateTime startDateTime = a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                LocalDateTime endDateTime = a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                LocalDateTime slotDateTime = LocalDateTime.of(targetDate, startTime);
                while (!slotDateTime.isAfter(LocalDateTime.of(targetDate, endTime))) {
                    if (!slotDateTime.isBefore(startDateTime) && slotDateTime.isBefore(endDateTime)) {
                        LocalTime slotTime = slotDateTime.toLocalTime();
                        StaffingBalanceDto balance = balanceMap.get(slotTime);
                        if (balance != null) balance.setAssignedStaff(balance.getAssignedStaff() + 1);
                    }
                    slotDateTime = slotDateTime.plusMinutes(resMin);
                }
            }
        }

        return new ArrayList<>(balanceMap.values());
    }

    private void addDepartmentTaskRequired(Map<LocalTime, StaffingBalanceDto> balanceMap,
                                           String storeCode,
                                           LocalDate targetDate,
                                           List<String> departments,
                                           int resMin) {
        if (departments == null || departments.isEmpty()) return;
        LocalTime startTime = LocalTime.of(8, 0);
        LocalTime endTime = LocalTime.of(23, 0).minusMinutes(resMin);
        for (String dept : departments) {
            if (dept == null || dept.isBlank()) continue;
            var dayAssignments = departmentTaskAssignmentMapper.selectByMonth(targetDate, targetDate.plusDays(1), storeCode, dept);
            for (var a : dayAssignments) {
                if ("BREAK".equalsIgnoreCase(a.getTaskCode())) continue;
                LocalDateTime startDateTime = a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                LocalDateTime endDateTime = a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                LocalDateTime slotDateTime = LocalDateTime.of(targetDate, startTime);
                while (!slotDateTime.isAfter(LocalDateTime.of(targetDate, endTime))) {
                    if (!slotDateTime.isBefore(startDateTime) && slotDateTime.isBefore(endDateTime)) {
                        LocalTime slotTime = slotDateTime.toLocalTime();
                        StaffingBalanceDto balance = balanceMap.get(slotTime);
                        if (balance != null) {
                            balance.setRequiredStaff(balance.getRequiredStaff() + 1);
                        }
                    }
                    slotDateTime = slotDateTime.plusMinutes(resMin);
                }
            }
        }
    }

    public List<StaffingBalanceDto> getStaffingBalanceForMonth(String storeCode, LocalDate startOfMonth) {
        List<StaffingBalanceDto> monthlyBalance = new ArrayList<>();
        
        LocalDate endOfMonth = startOfMonth.withDayOfMonth(startOfMonth.lengthOfMonth());
        LocalDate current = startOfMonth;
        
        while (!current.isAfter(endOfMonth)) {
            List<StaffingBalanceDto> dailyBalance = getStaffingBalance(storeCode, current);
            monthlyBalance.addAll(dailyBalance);
            current = current.plusDays(1);
        }
        
        return monthlyBalance;
    }

    public Map<LocalDate, DailyStaffingSummary> getDailyStaffingSummaryForMonth(String storeCode, LocalDate startOfMonth) {
        Map<LocalDate, DailyStaffingSummary> summaryMap = new LinkedHashMap<>();
        
        LocalDate endOfMonth = startOfMonth.withDayOfMonth(startOfMonth.lengthOfMonth());
        LocalDate current = startOfMonth;
        int resMin = appSettingService.getTimeResolutionMinutes();
        
        while (!current.isAfter(endOfMonth)) {
            List<StaffingBalanceDto> dailyBalance = getStaffingBalance(storeCode, current);
            
            int totalRequired = dailyBalance.stream().mapToInt(StaffingBalanceDto::getRequiredStaff).sum();
            int totalAssigned = dailyBalance.stream().mapToInt(StaffingBalanceDto::getAssignedStaff).sum();
            int shortageSlots = (int) dailyBalance.stream()
                    .mapToInt(b -> b.getAssignedStaff() - b.getRequiredStaff())
                    .filter(balance -> balance < 0).count();
            int overstaffSlots = (int) dailyBalance.stream()
                    .mapToInt(b -> b.getAssignedStaff() - b.getRequiredStaff())
                    .filter(balance -> balance > 0).count();
            int totalShortage = dailyBalance.stream()
                    .mapToInt(b -> Math.max(0, b.getRequiredStaff() - b.getAssignedStaff()))
                    .sum();
            int totalExcess = dailyBalance.stream()
                    .mapToInt(b -> Math.max(0, b.getAssignedStaff() - b.getRequiredStaff()))
                    .sum();
            int totalShortageMinutes = totalShortage * resMin;
            int totalExcessMinutes = totalExcess * resMin;
            
            DailyStaffingSummary summary = DailyStaffingSummary.builder()
                    .date(current)
                    .totalRequired(totalRequired)
                    .totalAssigned(totalAssigned)
                    .shortageSlots(shortageSlots)
                    .overstaffSlots(overstaffSlots)
                    .totalShortageMinutes(totalShortageMinutes)
                    .totalExcessMinutes(totalExcessMinutes)
                    .build();
            
            summaryMap.put(current, summary);
            current = current.plusDays(1);
        }
        
        return summaryMap;
    }

    public Map<LocalDate, DailyStaffingSummary> getDailyStaffingSummaryForMonth(String storeCode, LocalDate startOfMonth, String departmentCode) {
        Map<LocalDate, DailyStaffingSummary> summaryMap = new LinkedHashMap<>();

        LocalDate endOfMonth = startOfMonth.withDayOfMonth(startOfMonth.lengthOfMonth());
        LocalDate current = startOfMonth;
        int resMin = appSettingService.getTimeResolutionMinutes();

        while (!current.isAfter(endOfMonth)) {
            List<StaffingBalanceDto> dailyBalance = getHourlyStaffingBalance(storeCode, current, departmentCode);

            int totalRequired = dailyBalance.stream().mapToInt(StaffingBalanceDto::getRequiredStaff).sum();
            int totalAssigned = dailyBalance.stream().mapToInt(StaffingBalanceDto::getAssignedStaff).sum();
            int shortageSlots = (int) dailyBalance.stream()
                    .mapToInt(b -> b.getAssignedStaff() - b.getRequiredStaff())
                    .filter(balance -> balance < 0).count();
            int overstaffSlots = (int) dailyBalance.stream()
                    .mapToInt(b -> b.getAssignedStaff() - b.getRequiredStaff())
                    .filter(balance -> balance > 0).count();
            int totalShortage = dailyBalance.stream()
                    .mapToInt(b -> Math.max(0, b.getRequiredStaff() - b.getAssignedStaff()))
                    .sum();
            int totalExcess = dailyBalance.stream()
                    .mapToInt(b -> Math.max(0, b.getAssignedStaff() - b.getRequiredStaff()))
                    .sum();
            int totalShortageMinutes = totalShortage * resMin;
            int totalExcessMinutes = totalExcess * resMin;

            DailyStaffingSummary summary = DailyStaffingSummary.builder()
                    .date(current)
                    .totalRequired(totalRequired)
                    .totalAssigned(totalAssigned)
                    .shortageSlots(shortageSlots)
                    .overstaffSlots(overstaffSlots)
                    .totalShortageMinutes(totalShortageMinutes)
                    .totalExcessMinutes(totalExcessMinutes)
                    .build();

            summaryMap.put(current, summary);
            current = current.plusDays(1);
        }

        return summaryMap;
    }

    public static class DailyStaffingSummary {
        private final LocalDate date;
        private final int totalRequired;
        private final int totalAssigned;
        private final int shortageSlots;
        private final int overstaffSlots;
        private final int totalShortageMinutes;
        private final int totalExcessMinutes;

        public DailyStaffingSummary(LocalDate date, int totalRequired, int totalAssigned, 
                                  int shortageSlots, int overstaffSlots, int totalShortageMinutes, int totalExcessMinutes) {
            this.date = date;
            this.totalRequired = totalRequired;
            this.totalAssigned = totalAssigned;
            this.shortageSlots = shortageSlots;
            this.overstaffSlots = overstaffSlots;
            this.totalShortageMinutes = totalShortageMinutes;
            this.totalExcessMinutes = totalExcessMinutes;
        }

        public static DailyStaffingSummaryBuilder builder() {
            return new DailyStaffingSummaryBuilder();
        }

        public LocalDate getDate() { return date; }
        public int getTotalRequired() { return totalRequired; }
        public int getTotalAssigned() { return totalAssigned; }
        public int getShortageSlots() { return shortageSlots; }
        public int getOverstaffSlots() { return overstaffSlots; }
        public int getTotalShortageMinutes() { return totalShortageMinutes; }
        public int getTotalExcessMinutes() { return totalExcessMinutes; }
        public int getBalance() { return totalAssigned - totalRequired; }

        public static class DailyStaffingSummaryBuilder {
            private LocalDate date;
            private int totalRequired;
            private int totalAssigned;
            private int shortageSlots;
        private int overstaffSlots;
        private int totalShortageMinutes;
        private int totalExcessMinutes;

            public DailyStaffingSummaryBuilder date(LocalDate date) {
                this.date = date;
                return this;
            }

            public DailyStaffingSummaryBuilder totalRequired(int totalRequired) {
                this.totalRequired = totalRequired;
                return this;
            }

            public DailyStaffingSummaryBuilder totalAssigned(int totalAssigned) {
                this.totalAssigned = totalAssigned;
                return this;
            }

            public DailyStaffingSummaryBuilder shortageSlots(int shortageSlots) {
                this.shortageSlots = shortageSlots;
                return this;
            }

            public DailyStaffingSummaryBuilder overstaffSlots(int overstaffSlots) {
                this.overstaffSlots = overstaffSlots;
                return this;
            }

        public DailyStaffingSummaryBuilder totalShortageMinutes(int totalShortageMinutes) {
            this.totalShortageMinutes = totalShortageMinutes;
            return this;
        }

        public DailyStaffingSummaryBuilder totalExcessMinutes(int totalExcessMinutes) {
            this.totalExcessMinutes = totalExcessMinutes;
            return this;
        }

        public DailyStaffingSummary build() {
            return new DailyStaffingSummary(date, totalRequired, totalAssigned, 
                                          shortageSlots, overstaffSlots, totalShortageMinutes, totalExcessMinutes);
        }
        }
    }
}
