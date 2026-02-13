package io.github.riemr.shift.infrastructure.persistence.entity;

import lombok.Data;

@Data
public class OrgNode {
    private Long nodeId;
    private String nodeCode;
    private String nodeName;
    private Long parentNodeId;
    private Integer hierarchyLevel;
    private Integer displayOrder;
    private Boolean isActive;
}
