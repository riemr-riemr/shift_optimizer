package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;

public class TaskMaster implements Serializable {
    private String taskCode;
    private String name;
    private String description;
    private String defaultScheduleType;
    private Integer defaultRequiredDurationMinutes;
    private Integer priority;
    private String color;
    private String icon;

    private static final long serialVersionUID = 1L;

    public String getTaskCode() { return taskCode; }
    public void setTaskCode(String taskCode) { this.taskCode = taskCode == null ? null : taskCode.trim(); }
    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? null : name.trim(); }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDefaultScheduleType() { return defaultScheduleType; }
    public void setDefaultScheduleType(String defaultScheduleType) { this.defaultScheduleType = defaultScheduleType; }
    public Integer getDefaultRequiredDurationMinutes() { return defaultRequiredDurationMinutes; }
    public void setDefaultRequiredDurationMinutes(Integer v) { this.defaultRequiredDurationMinutes = v; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
}
