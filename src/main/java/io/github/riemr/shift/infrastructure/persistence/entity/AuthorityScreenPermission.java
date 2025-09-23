package io.github.riemr.shift.infrastructure.persistence.entity;

import lombok.Data;

@Data
public class AuthorityScreenPermission {
    private String authorityCode;
    private String screenCode;
    private Boolean canView;
    private Boolean canUpdate;
}
