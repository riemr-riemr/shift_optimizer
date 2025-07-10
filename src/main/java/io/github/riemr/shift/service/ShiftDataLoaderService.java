package io.github.riemr.shift.service;

import io.github.riemr.shift.domain.model.*;
import io.github.riemr.shift.repository.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ShiftDataLoaderService {

    private final EmployeeRepository employeeRepository;
    private final RegisterRepository registerRepository;
    private final RegisterDemandRepository demandRepository;

    public ShiftDataLoaderService(EmployeeRepository employeeRepository,
                                  RegisterRepository registerRepository,
                                  RegisterDemandRepository demandRepository) {
        this.employeeRepository = employeeRepository;
        this.registerRepository = registerRepository;
        this.demandRepository = demandRepository;
    }

    public ShiftSchedule load(String storeCode, LocalDate from, LocalDate to) {
        List<Employee> employees  = employeeRepository.findByStore_StoreCode(storeCode);
        List<Register> registers  = registerRepository.findByStoreCode(storeCode);
        List<RegisterDemand> demands = demandRepository.findByStoreCodeAndDateBetween(storeCode, from, to);

        // demand × registerNo でプレースホルダーを生成（従業員未割当＝null）
        List<ShiftAssignment> shifts = demands.stream().flatMap(d -> {
            LocalDateTime start = LocalDateTime.of(d.getDate(), d.getTime());
            LocalDateTime end   = start.plusMinutes(15);
            return registers.stream()
                    .filter(Register::isAutoOpenTarget)
                    .map(reg -> {
                        ShiftAssignment sa = new ShiftAssignment();
                        sa.setId(new ShiftAssignmentId(storeCode, null, start)); // employeeCode は割当後に OptaPlanner が埋める
                        sa.setEndAt(end);
                        sa.setRegister(reg);
                        return sa;
                    });
        }).toList();

        return new ShiftSchedule(employees, registers, demands, shifts);
    }
}