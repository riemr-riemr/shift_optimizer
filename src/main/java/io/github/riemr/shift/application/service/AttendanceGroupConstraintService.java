package io.github.riemr.shift.application.service;

import io.github.riemr.shift.application.dto.AttendanceGroupConstraintView;
import io.github.riemr.shift.infrastructure.mapper.AttendanceGroupConstraintMapper;
import io.github.riemr.shift.infrastructure.mapper.AttendanceGroupMemberMapper;
import io.github.riemr.shift.infrastructure.mapper.DepartmentMasterMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.infrastructure.mapper.StoreMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.AttendanceGroupConstraint;
import io.github.riemr.shift.infrastructure.persistence.entity.AttendanceGroupMember;
import io.github.riemr.shift.infrastructure.persistence.entity.DepartmentMaster;
import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.Store;
import io.github.riemr.shift.infrastructure.persistence.entity.StoreExample;
import io.github.riemr.shift.optimization.entity.AttendanceGroupRuleType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AttendanceGroupConstraintService {
    private final AttendanceGroupConstraintMapper constraintMapper;
    private final AttendanceGroupMemberMapper memberMapper;
    private final EmployeeMapper employeeMapper;
    private final StoreMapper storeMapper;
    private final DepartmentMasterMapper departmentMasterMapper;

    public List<Store> listStores() {
        StoreExample example = new StoreExample();
        example.setOrderByClause("CAST(store_code AS INTEGER) ASC");
        return storeMapper.selectByExample(example);
    }

    public List<DepartmentMaster> listDepartments() {
        return departmentMasterMapper.selectAll();
    }

    public List<Employee> listEmployees() {
        return employeeMapper.selectAll();
    }

    public List<AttendanceGroupConstraintView> listConstraints(String storeCode, String departmentCode) {
        if (storeCode == null || storeCode.isBlank()) {
            return List.of();
        }
        List<AttendanceGroupConstraint> constraints =
                constraintMapper.selectByStoreAndDepartment(storeCode, departmentCode);
        if (constraints.isEmpty()) {
            return List.of();
        }
        List<Long> ids = constraints.stream()
                .map(AttendanceGroupConstraint::getConstraintId)
                .filter(Objects::nonNull)
                .toList();
        List<AttendanceGroupMember> members = ids.isEmpty()
                ? List.of()
                : memberMapper.selectByConstraintIds(ids);
        Map<Long, List<String>> memberCodesByConstraint = new HashMap<>();
        for (var m : members) {
            if (m.getConstraintId() == null || m.getEmployeeCode() == null) continue;
            memberCodesByConstraint
                    .computeIfAbsent(m.getConstraintId(), k -> new ArrayList<>())
                    .add(m.getEmployeeCode());
        }
        Map<String, String> employeeNameMap = employeeMapper.selectAll().stream()
                .filter(e -> e.getEmployeeCode() != null)
                .collect(Collectors.toMap(Employee::getEmployeeCode,
                        e -> e.getEmployeeName() == null ? "" : e.getEmployeeName(),
                        (a, b) -> a));

        List<AttendanceGroupConstraintView> views = new ArrayList<>();
        for (var c : constraints) {
            AttendanceGroupConstraintView view = new AttendanceGroupConstraintView();
            view.setConstraintId(c.getConstraintId());
            view.setStoreCode(c.getStoreCode());
            view.setDepartmentCode(c.getDepartmentCode());
            view.setRuleType(c.getRuleType());
            view.setMinOnDuty(c.getMinOnDuty());
            List<String> codes = memberCodesByConstraint.getOrDefault(c.getConstraintId(), List.of());
            List<String> labels = new ArrayList<>();
            for (var code : codes) {
                String name = employeeNameMap.getOrDefault(code, "");
                if (name == null || name.isBlank()) {
                    labels.add(code);
                } else {
                    labels.add(code + " " + name);
                }
            }
            view.setMemberLabels(labels);
            views.add(view);
        }
        return views;
    }

    @Transactional
    public void createConstraint(String storeCode,
                                 String departmentCode,
                                 String ruleTypeCode,
                                 Integer minOnDuty,
                                 List<String> memberEmployeeCodes) {
        if (storeCode == null || storeCode.isBlank()) {
            throw new IllegalArgumentException("店舗コードは必須です");
        }
        AttendanceGroupRuleType ruleType = AttendanceGroupRuleType.fromCode(ruleTypeCode);
        if (ruleType == null) {
            throw new IllegalArgumentException("制約種別が不正です");
        }
        Set<String> members = new HashSet<>();
        if (memberEmployeeCodes != null) {
            for (var code : memberEmployeeCodes) {
                if (code != null && !code.isBlank()) {
                    members.add(code.trim());
                }
            }
        }
        if (members.size() < 2) {
            throw new IllegalArgumentException("従業員は2名以上選択してください");
        }
        Integer normalizedMinOnDuty = null;
        if (ruleType == AttendanceGroupRuleType.MIN_ON_DUTY) {
            if (minOnDuty == null) {
                throw new IllegalArgumentException("最低出勤人数を入力してください");
            }
            int normalized = Math.max(1, Math.min(minOnDuty, members.size()));
            normalizedMinOnDuty = normalized;
        }
        AttendanceGroupConstraint row = new AttendanceGroupConstraint();
        row.setStoreCode(storeCode.trim());
        row.setDepartmentCode((departmentCode == null || departmentCode.isBlank()) ? null : departmentCode.trim());
        row.setRuleType(ruleType.name());
        row.setMinOnDuty(normalizedMinOnDuty);
        constraintMapper.insert(row);
        if (row.getConstraintId() == null) {
            throw new IllegalStateException("制約の登録に失敗しました");
        }
        List<AttendanceGroupMember> memberRows = new ArrayList<>();
        for (var code : members) {
            AttendanceGroupMember member = new AttendanceGroupMember();
            member.setConstraintId(row.getConstraintId());
            member.setEmployeeCode(code);
            memberRows.add(member);
        }
        memberMapper.insertAll(memberRows);
    }

    @Transactional
    public void deleteConstraint(Long constraintId) {
        if (constraintId == null) return;
        memberMapper.deleteByConstraintId(constraintId);
        constraintMapper.deleteByPrimaryKey(constraintId);
    }
}
