package io.github.riemr.shift.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Data;

import java.io.Serializable;

@Data
@Embeddable
public class EmployeeRegisterSkillId implements Serializable {
    @Column(name = "store_code")
    private String storeCode;

    @Column(name = "employee_code")
    private String employeeCode;

    @Column(name = "register_no")
    private Integer registerNo;
}