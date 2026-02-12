package io.github.riemr.shift.application.service;

import io.github.riemr.shift.infrastructure.mapper.AuthorityDepartmentPermissionMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.DepartmentMaster;
import io.github.riemr.shift.infrastructure.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class DepartmentAuthorizationService {
    private final AuthorityDepartmentPermissionMapper authorityDepartmentPermissionMapper;
    private final EmployeeMapper employeeMapper;

    public List<DepartmentMaster> filterAccessibleDepartments(List<DepartmentMaster> departments) {
        AuthUser user = currentUser();
        if (user == null) {
            return List.of();
        }
        if ("ADMIN".equalsIgnoreCase(user.getAuthorityCode())) {
            return departments;
        }
        Set<String> allowed = new HashSet<>(authorityDepartmentPermissionMapper.findDepartmentCodesByAuthority(user.getAuthorityCode()));
        return departments.stream()
                .filter(d -> allowed.contains(d.getDepartmentCode()))
                .toList();
    }

    public boolean canAccessDepartment(String departmentCode) {
        if (departmentCode == null || departmentCode.isBlank()) {
            return true;
        }
        AuthUser user = currentUser();
        if (user == null) {
            return false;
        }
        if ("ADMIN".equalsIgnoreCase(user.getAuthorityCode())) {
            return true;
        }
        return authorityDepartmentPermissionMapper.findDepartmentCodesByAuthority(user.getAuthorityCode())
                .contains(departmentCode);
    }

    private AuthUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return null;
        }
        return employeeMapper.selectAuthByEmployeeCode(auth.getName());
    }
}
