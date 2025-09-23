package io.github.riemr.shift.application.service;

import io.github.riemr.shift.infrastructure.mapper.AuthorityScreenPermissionMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.AuthorityScreenPermission;
import io.github.riemr.shift.infrastructure.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component("screenAuth")
@RequiredArgsConstructor
public class ScreenAuthorizationService {
    private final AuthorityScreenPermissionMapper permissionMapper;
    private final EmployeeMapper employeeMapper;

    public boolean hasViewPermission(String screenCode) {
        AuthUser user = currentUser();
        if (user == null) return false;
        // ADMINは常に許可（念のためのデフォルト）
        if ("ADMIN".equalsIgnoreCase(user.getAuthorityCode())) return true;
        AuthorityScreenPermission p = permissionMapper.find(user.getAuthorityCode(), screenCode);
        return p != null && Boolean.TRUE.equals(p.getCanView());
    }

    public boolean hasUpdatePermission(String screenCode) {
        AuthUser user = currentUser();
        if (user == null) return false;
        if ("ADMIN".equalsIgnoreCase(user.getAuthorityCode())) return true;
        AuthorityScreenPermission p = permissionMapper.find(user.getAuthorityCode(), screenCode);
        return p != null && Boolean.TRUE.equals(p.getCanUpdate());
    }

    private AuthUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return null;
        return employeeMapper.selectAuthByEmployeeCode(auth.getName());
    }
}
