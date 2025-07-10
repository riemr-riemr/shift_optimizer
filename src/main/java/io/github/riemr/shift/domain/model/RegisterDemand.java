package io.github.riemr.shift.domain.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@Entity
@Table(name = "register_demand_quarter")
public class RegisterDemand {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "demand_id")
    private Long demandId;

    @Column(name = "store_code", nullable = false)
    private String storeCode;

    @Column(name = "demand_date", nullable = false)
    private LocalDate date;

    @Column(name = "slot_time", nullable = false)
    private LocalTime time;

    @Column(name = "required_units", nullable = false)
    private Integer requiredUnits;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_code", insertable = false, updatable = false)
    private Store store;
}