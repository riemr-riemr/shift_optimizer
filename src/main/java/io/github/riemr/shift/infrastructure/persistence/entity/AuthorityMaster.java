package io.github.riemr.shift.infrastructure.persistence.entity;

import lombok.Data;

@Data
public class AuthorityMaster {
    private String authorityCode;
    private String authorityName;
    private String description;
}

