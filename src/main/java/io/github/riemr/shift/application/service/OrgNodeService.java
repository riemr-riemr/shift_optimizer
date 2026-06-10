package io.github.riemr.shift.application.service;

import io.github.riemr.shift.infrastructure.mapper.OrgNodeMapper;
import io.github.riemr.shift.infrastructure.mapper.StoreOrgNodeMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.OrgNode;
import io.github.riemr.shift.infrastructure.persistence.entity.StoreOrgNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrgNodeService {
    private final OrgNodeMapper orgNodeMapper;
    private final StoreOrgNodeMapper storeOrgNodeMapper;

    public List<OrgNode> listAll() {
        return orgNodeMapper.selectAll();
    }

    @Transactional
    public OrgNode create(String nodeCode, String nodeName, Long parentNodeId, Integer displayOrder, Boolean isActive) {
        validateNodeCode(nodeCode);
        if (orgNodeMapper.selectByCode(nodeCode) != null) {
            throw new IllegalArgumentException("同じノードコードが既に存在します。");
        }
        int level = resolveLevel(parentNodeId);
        OrgNode row = new OrgNode();
        row.setNodeCode(nodeCode.trim());
        row.setNodeName(requireText(nodeName, "ノード名は必須です。"));
        row.setParentNodeId(parentNodeId);
        row.setHierarchyLevel(level);
        row.setDisplayOrder(displayOrder);
        row.setIsActive(isActive == null ? Boolean.TRUE : isActive);
        orgNodeMapper.insert(row);
        return row;
    }

    @Transactional
    public void update(Long nodeId, String nodeName, Long parentNodeId, Integer displayOrder, Boolean isActive) {
        OrgNode current = requireNode(nodeId);
        if (parentNodeId != null && parentNodeId.equals(nodeId)) {
            throw new IllegalArgumentException("自身を親ノードに設定できません。");
        }
        if (parentNodeId != null && isDescendant(nodeId, parentNodeId)) {
            throw new IllegalArgumentException("子孫ノードを親に設定できません。");
        }

        int newLevel = resolveLevel(parentNodeId);
        current.setNodeName(requireText(nodeName, "ノード名は必須です。"));
        current.setParentNodeId(parentNodeId);
        current.setHierarchyLevel(newLevel);
        current.setDisplayOrder(displayOrder);
        current.setIsActive(isActive == null ? Boolean.TRUE : isActive);
        orgNodeMapper.update(current);

        refreshDescendantLevels(current.getNodeId(), current.getHierarchyLevel());
    }

    @Transactional
    public void delete(Long nodeId) {
        OrgNode target = requireNode(nodeId);
        if (!orgNodeMapper.selectChildren(nodeId).isEmpty()) {
            throw new IllegalArgumentException("子ノードが存在するため削除できません。");
        }
        if (!storeOrgNodeMapper.findStoreCodesByNode(nodeId).isEmpty()) {
            throw new IllegalArgumentException("店舗が紐づいているため削除できません。");
        }
        orgNodeMapper.deleteById(target.getNodeId());
    }

    @Transactional
    public void assignStore(String storeCode, Long nodeId) {
        requireText(storeCode, "店舗コードは必須です。");
        requireNode(nodeId);
        StoreOrgNode mapping = new StoreOrgNode();
        mapping.setStoreCode(storeCode.trim());
        mapping.setNodeId(nodeId);
        storeOrgNodeMapper.upsert(mapping);
    }

    public boolean isDescendant(Long rootNodeId, Long maybeDescendantId) {
        if (rootNodeId == null || maybeDescendantId == null) {
            return false;
        }
        List<OrgNode> all = orgNodeMapper.selectAll();
        Map<Long, Long> parentById = new HashMap<>();
        for (OrgNode node : all) {
            parentById.put(node.getNodeId(), node.getParentNodeId());
        }
        Long cursor = maybeDescendantId;
        while (cursor != null) {
            if (rootNodeId.equals(cursor)) {
                return true;
            }
            cursor = parentById.get(cursor);
        }
        return false;
    }

    private void refreshDescendantLevels(Long parentNodeId, int parentLevel) {
        List<OrgNode> children = orgNodeMapper.selectChildren(parentNodeId);
        for (OrgNode child : children) {
            child.setHierarchyLevel(parentLevel + 1);
            orgNodeMapper.update(child);
            refreshDescendantLevels(child.getNodeId(), child.getHierarchyLevel());
        }
    }

    private int resolveLevel(Long parentNodeId) {
        if (parentNodeId == null) {
            return 0;
        }
        OrgNode parent = requireNode(parentNodeId);
        return (parent.getHierarchyLevel() == null ? 0 : parent.getHierarchyLevel()) + 1;
    }

    private OrgNode requireNode(Long nodeId) {
        OrgNode node = orgNodeMapper.selectById(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("指定したノードが存在しません。");
        }
        return node;
    }

    private void validateNodeCode(String value) {
        String code = requireText(value, "ノードコードは必須です。");
        if (!code.matches("^[A-Z0-9_\\-]{2,32}$")) {
            throw new IllegalArgumentException("ノードコードは2〜32文字の英大文字・数字・_・-で入力してください。");
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
