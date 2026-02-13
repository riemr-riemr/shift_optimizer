package io.github.riemr.shift.infrastructure.persistence.entity;

import lombok.Data;

@Data
public class AuthorityNodePermission {
    private String authorityCode;
    private Long nodeId;
    private Boolean canViewStore;
    private Boolean canManageStore;
}
