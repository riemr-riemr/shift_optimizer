package io.github.riemr.shift.domain.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "employee")
public class Employee {
    @Id
    @Column(name = "employee_code")
    private String employeeCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_code", nullable = false)
    private Store store;

    @Column(name = "employee_name", nullable = false)
    private String employeeName;

    @Column(name = "short_follow")
    private Short shortFollow;

    @Column(name = "max_work_minutes_day")
    private Integer maxWorkMinutesDay;

    @Column(name = "max_work_days_month")
    private Integer maxWorkDaysMonth;
}