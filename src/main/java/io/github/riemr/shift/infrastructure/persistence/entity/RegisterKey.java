package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
public class RegisterKey implements Serializable {
    private String storeCode;
    private Integer registerNo;

    private static final long serialVersionUID = 1L;

    public String getStoreCode() {
        return storeCode;
    }

    public void setStoreCode(String storeCode) {
        this.storeCode = storeCode;
    }

    public Integer getRegisterNo() {
        return registerNo;
    }

    public void setRegisterNo(Integer registerNo) {
        this.registerNo = registerNo;
    }
}