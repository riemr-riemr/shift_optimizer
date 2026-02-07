package io.github.riemr.shift.infrastructure.security;

import lombok.Data;

@Data
public class AuthUser {
    private String employeeCode;   // username
    private String employeeName;
    private String storeCode;
    private String passwordHash;
    private String authorityCode;  // ADMIN / MANAGER / USER
}

