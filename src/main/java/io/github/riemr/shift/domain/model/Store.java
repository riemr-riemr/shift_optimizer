package io.github.riemr.shift.domain.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "store")
public class Store {
    @Id
    @Column(name = "store_code")
    private String storeCode;

    @Column(name = "store_name", nullable = false)
    private String storeName;

    @Column(name = "timezone", nullable = false)
    private String timezone = "Asia/Tokyo";
}