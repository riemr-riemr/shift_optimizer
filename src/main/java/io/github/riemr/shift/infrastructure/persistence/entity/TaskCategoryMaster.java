package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;

public class TaskCategoryMaster implements Serializable {
    private String categoryCode;
    private String categoryName;
    private Integer displayOrder;
    private String color;
    private String icon;
    private Boolean active;

    private static final long serialVersionUID = 1L;

    public String getCategoryCode() { return categoryCode; }
    public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}

