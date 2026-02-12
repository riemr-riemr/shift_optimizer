package io.github.riemr.shift.infrastructure.persistence.entity;

import lombok.Data;

@Data
public class AuthorityDepartmentPermission {
    private String authorityCode;
    private String departmentCode;
}
