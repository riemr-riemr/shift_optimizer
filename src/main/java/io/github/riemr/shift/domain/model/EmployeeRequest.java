package io.github.riemr.shift.domain.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

@Data
@Entity
@Table(name = "employee_request")
public class EmployeeRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "store_code", nullable = false)
    private String storeCode;

    @Column(name = "employee_code", nullable = false)
    private String employeeCode;

    @Column(name = "request_date", nullable = false)
    private LocalDate requestDate;

    @Column(name = "from_time")
    private LocalTime fromTime;

    @Column(name = "to_time")
    private LocalTime toTime;

    @Column(name = "request_kind", nullable = false)
    private String requestKind;

    @Column(name = "priority", nullable = false)
    private Integer priority = 2;

    @Column(name = "note")
    private String note;

    // FK convenience
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "store_code", referencedColumnName = "store_code", insertable = false, updatable = false),
            @JoinColumn(name = "employee_code", referencedColumnName = "employee_code", insertable = false, updatable = false)
    })
    private Employee employee;

    // getters & setters ... (省略可)
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof EmployeeRequest r))
            return false;
        return Objects.equals(requestId, r.requestId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestId);
    }
}