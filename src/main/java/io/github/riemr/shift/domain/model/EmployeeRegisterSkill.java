package io.github.riemr.shift.domain.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "employee_register_skill")
public class EmployeeRegisterSkill {
    @EmbeddedId
    private EmployeeRegisterSkillId id;

    @MapsId("employeeCode")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_code")
    private Employee employee;

    @MapsId("registerNo")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "store_code", referencedColumnName = "store_code", insertable = false, updatable = false),
            @JoinColumn(name = "register_no", referencedColumnName = "register_no")
    })
    private Register register;

    @Column(name = "skill_level")
    private Short skillLevel;
}