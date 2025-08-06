package io.github.riemr.shift.application.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import io.github.riemr.shift.application.dto.StaffingBalanceDto;
import io.github.riemr.shift.infrastructure.mapper.RegisterDemandQuarterMapper;
import io.github.riemr.shift.infrastructure.mapper.ShiftAssignmentMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterDemandQuarter;
import io.github.riemr.shift.infrastructure.persistence.entity.ShiftAssignment;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StaffingBalanceService {
    
    private final RegisterDemandQuarterMapper demandMapper;
    private final ShiftAssignmentMapper shiftMapper;

    public List<StaffingBalanceDto> getStaffingBalance(String storeCode, LocalDate targetDate) {
        List<RegisterDemandQuarter> demands = demandMapper.selectByStoreAndDate(storeCode, targetDate);
        List<ShiftAssignment> assignments = shiftMapper.selectByDate(targetDate);

        Map<LocalTime, StaffingBalanceDto> balanceMap = new LinkedHashMap<>();

        LocalTime startTime = LocalTime.of(8, 0);
        LocalTime endTime = LocalTime.of(22, 45);
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
            current = current.plusMinutes(15);
        }

        for (RegisterDemandQuarter demand : demands) {
            LocalTime slotTime = demand.getSlotTime();
            StaffingBalanceDto balance = balanceMap.get(slotTime);
            if (balance != null) {
                balance.setRequiredStaff(demand.getRequiredUnits());
            }
        }

        for (ShiftAssignment assignment : assignments) {
            if (!storeCode.equals(assignment.getStoreCode())) {
                continue;
            }

            LocalDateTime startDateTime = assignment.getStartAt().toInstant()
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
            LocalDateTime endDateTime = assignment.getEndAt().toInstant()
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
            
            if (!startDateTime.toLocalDate().equals(targetDate) && !endDateTime.toLocalDate().equals(targetDate)) {
                continue;
            }

            LocalDateTime slotDateTime = LocalDateTime.of(targetDate, startTime);
            while (!slotDateTime.isAfter(LocalDateTime.of(targetDate, endTime))) {
                if (!slotDateTime.isBefore(startDateTime) && slotDateTime.isBefore(endDateTime)) {
                    LocalTime slotTime = slotDateTime.toLocalTime();
                    StaffingBalanceDto balance = balanceMap.get(slotTime);
                    if (balance != null) {
                        balance.setAssignedStaff(balance.getAssignedStaff() + 1);
                    }
                }
                slotDateTime = slotDateTime.plusMinutes(15);
            }
        }

        return new ArrayList<>(balanceMap.values());
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
}