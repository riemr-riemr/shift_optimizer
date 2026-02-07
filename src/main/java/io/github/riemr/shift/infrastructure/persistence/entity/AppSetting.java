package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;
import java.util.Date;

public class AppSetting implements Serializable {
    private String settingKey;
    private String settingValue;
    private Date updatedAt;

    public String getSettingKey() { return settingKey; }
    public void setSettingKey(String settingKey) { this.settingKey = settingKey; }
    public String getSettingValue() { return settingValue; }
    public void setSettingValue(String settingValue) { this.settingValue = settingValue; }
    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}

