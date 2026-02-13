package io.github.riemr.shift.application.service;

import io.github.riemr.shift.infrastructure.mapper.AuthorityNodePermissionMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.infrastructure.mapper.OrgNodeMapper;
import io.github.riemr.shift.infrastructure.mapper.StoreOrgNodeMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.AuthorityNodePermission;
import io.github.riemr.shift.infrastructure.persistence.entity.OrgNode;
import io.github.riemr.shift.infrastructure.persistence.entity.Store;
import io.github.riemr.shift.infrastructure.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class StoreAuthorizationService {
    private final EmployeeMapper employeeMapper;
    private final AuthorityNodePermissionMapper authorityNodePermissionMapper;
    private final OrgNodeMapper orgNodeMapper;
    private final StoreOrgNodeMapper storeOrgNodeMapper;

    public List<Store> filterViewableStores(List<Store> stores) {
        return filterStores(stores, false);
    }

    public List<Store> filterManageableStores(List<Store> stores) {
        return filterStores(stores, true);
    }

    public boolean canViewStore(String storeCode) {
        return hasStorePermission(storeCode, false);
    }

    public boolean canManageStore(String storeCode) {
        return hasStorePermission(storeCode, true);
    }

    private List<Store> filterStores(List<Store> stores, boolean requireManage) {
        AuthUser user = currentUser();
        if (user == null) {
            return List.of();
        }
        if ("ADMIN".equalsIgnoreCase(user.getAuthorityCode())) {
            return stores;
        }
        PermissionContext ctx = buildPermissionContext(user.getAuthorityCode());
        return stores.stream()
                .filter(s -> hasStorePermission(s == null ? null : s.getStoreCode(), requireManage, ctx))
                .toList();
    }

    private boolean hasStorePermission(String storeCode, boolean requireManage) {
        AuthUser user = currentUser();
        if (user == null || storeCode == null || storeCode.isBlank()) {
            return false;
        }
        if ("ADMIN".equalsIgnoreCase(user.getAuthorityCode())) {
            return true;
        }
        PermissionContext ctx = buildPermissionContext(user.getAuthorityCode());
        return hasStorePermission(storeCode, requireManage, ctx);
    }

    private boolean hasStorePermission(String storeCode, boolean requireManage, PermissionContext ctx) {
        if (ctx == null || storeCode == null || storeCode.isBlank()) {
            return false;
        }
        Long nodeId = ctx.nodeIdByStoreCode.get(storeCode);
        if (nodeId == null) {
            return false;
        }
        Long cursor = nodeId;
        while (cursor != null) {
            if (ctx.manageNodeIds.contains(cursor)) {
                return true;
            }
            if (!requireManage && ctx.viewNodeIds.contains(cursor)) {
                return true;
            }
            cursor = ctx.parentByNodeId.get(cursor);
        }
        return false;
    }

    private PermissionContext buildPermissionContext(String authorityCode) {
        List<AuthorityNodePermission> permissions = authorityNodePermissionMapper.findAllByAuthority(authorityCode);
        List<OrgNode> nodes = orgNodeMapper.selectAll();
        Map<Long, Long> parentByNodeId = new HashMap<>();
        for (OrgNode node : nodes) {
            parentByNodeId.put(node.getNodeId(), node.getParentNodeId());
        }
        Map<String, Long> nodeIdByStoreCode = new HashMap<>();
        for (var mapping : storeOrgNodeMapper.selectAll()) {
            nodeIdByStoreCode.put(mapping.getStoreCode(), mapping.getNodeId());
        }

        Set<Long> viewNodeIds = new HashSet<>();
        Set<Long> manageNodeIds = new HashSet<>();
        for (AuthorityNodePermission p : permissions) {
            if (Boolean.TRUE.equals(p.getCanViewStore())) {
                viewNodeIds.add(p.getNodeId());
            }
            if (Boolean.TRUE.equals(p.getCanManageStore())) {
                manageNodeIds.add(p.getNodeId());
            }
        }

        return new PermissionContext(parentByNodeId, nodeIdByStoreCode, viewNodeIds, manageNodeIds);
    }

    private AuthUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return null;
        }
        return employeeMapper.selectAuthByEmployeeCode(auth.getName());
    }

    private record PermissionContext(
            Map<Long, Long> parentByNodeId,
            Map<String, Long> nodeIdByStoreCode,
            Set<Long> viewNodeIds,
            Set<Long> manageNodeIds
    ) {
    }
}
