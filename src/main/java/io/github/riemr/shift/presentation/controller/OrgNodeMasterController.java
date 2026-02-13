package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.service.OrgNodeService;
import io.github.riemr.shift.infrastructure.mapper.StoreMapper;
import io.github.riemr.shift.infrastructure.mapper.StoreOrgNodeMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.OrgNode;
import io.github.riemr.shift.infrastructure.persistence.entity.Store;
import io.github.riemr.shift.infrastructure.persistence.entity.StoreExample;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/masters/org-node")
@RequiredArgsConstructor
public class OrgNodeMasterController {
    private final OrgNodeService orgNodeService;
    private final StoreMapper storeMapper;
    private final StoreOrgNodeMapper storeOrgNodeMapper;

    @GetMapping
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).TASK_MASTER)")
    public String view(Model model) {
        List<OrgNode> nodes = orgNodeService.listAll();
        List<Store> stores = storeMapper.selectByExample(new StoreExample());
        stores.sort(Comparator.comparing(Store::getStoreCode));

        Map<String, Long> assignedNodeByStore = new HashMap<>();
        for (var m : storeOrgNodeMapper.selectAll()) {
            assignedNodeByStore.put(m.getStoreCode(), m.getNodeId());
        }

        Map<Long, Integer> storeCountByNode = new HashMap<>();
        for (var m : storeOrgNodeMapper.selectAll()) {
            storeCountByNode.merge(m.getNodeId(), 1, Integer::sum);
        }

        model.addAttribute("nodes", nodes);
        model.addAttribute("stores", stores);
        model.addAttribute("assignedNodeByStore", assignedNodeByStore);
        model.addAttribute("storeCountByNode", storeCountByNode);
        return "masters/org-node";
    }

    @PostMapping("/api/nodes")
    @ResponseBody
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).TASK_MASTER)")
    public Map<String, Object> createNode(@RequestBody NodeSaveRequest request) {
        try {
            OrgNode created = orgNodeService.create(request.nodeCode(), request.nodeName(), request.parentNodeId(), request.displayOrder(), request.isActive());
            return Map.of("success", true, "nodeId", created.getNodeId());
        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    @PostMapping("/api/nodes/{nodeId}")
    @ResponseBody
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).TASK_MASTER)")
    public Map<String, Object> updateNode(@PathVariable("nodeId") Long nodeId,
                                          @RequestBody NodeUpdateRequest request) {
        try {
            orgNodeService.update(nodeId, request.nodeName(), request.parentNodeId(), request.displayOrder(), request.isActive());
            return Map.of("success", true);
        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    @PostMapping("/api/nodes/{nodeId}/delete")
    @ResponseBody
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).TASK_MASTER)")
    public Map<String, Object> deleteNode(@PathVariable("nodeId") Long nodeId) {
        try {
            orgNodeService.delete(nodeId);
            return Map.of("success", true);
        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    @PostMapping("/api/stores/{storeCode}/assign")
    @ResponseBody
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).TASK_MASTER)")
    public Map<String, Object> assignStore(@PathVariable("storeCode") String storeCode,
                                           @RequestBody AssignStoreRequest request) {
        try {
            orgNodeService.assignStore(storeCode, request.nodeId());
            return Map.of("success", true);
        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    public record NodeSaveRequest(String nodeCode, String nodeName, Long parentNodeId, Integer displayOrder, Boolean isActive) {}
    public record NodeUpdateRequest(String nodeName, Long parentNodeId, Integer displayOrder, Boolean isActive) {}
    public record AssignStoreRequest(Long nodeId) {}
}
