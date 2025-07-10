package io.github.riemr.shift.domain.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "register")
public class Register {
    @EmbeddedId
    private RegisterId id;

    @MapsId("storeCode")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_code")
    private Store store;

    @Column(name = "register_name", nullable = false)
    private String registerName;

    @Column(name = "short_name")
    private String shortName;

    @Column(name = "open_priority", nullable = false)
    private Integer openPriority = 99;

    @Column(name = "register_type", nullable = false)
    private String registerType;

    @Column(name = "is_auto_open_target", nullable = false)
    private boolean autoOpenTarget = true;
}