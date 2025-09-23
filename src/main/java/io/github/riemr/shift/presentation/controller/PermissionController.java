package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.infrastructure.mapper.AuthorityMasterMapper;
import io.github.riemr.shift.infrastructure.mapper.AuthorityScreenPermissionMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.AuthorityMaster;
import io.github.riemr.shift.infrastructure.persistence.entity.AuthorityScreenPermission;
import io.github.riemr.shift.util.ScreenCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

@Controller
@RequestMapping("/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final AuthorityMasterMapper authorityMasterMapper;
    private final AuthorityScreenPermissionMapper permissionMapper;

    private static final List<String> SCREENS = List.of(
            ScreenCodes.SHIFT_MONTHLY,
            ScreenCodes.SHIFT_DAILY,
            ScreenCodes.EMPLOYEE_LIST,
            ScreenCodes.EMPLOYEE_SHIFT,
            ScreenCodes.EMPLOYEE_REQUEST,
            ScreenCodes.SKILL_MATRIX,
            ScreenCodes.REGISTER_DEMAND,
            ScreenCodes.STAFFING_BALANCE,
            ScreenCodes.SETTINGS,
            ScreenCodes.SCREEN_PERMISSION
    );

    @GetMapping
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).SCREEN_PERMISSION)")
    public String view(Model model) {
        List<AuthorityMaster> roles = authorityMasterMapper.selectAll();
        model.addAttribute("roles", roles);
        model.addAttribute("screens", SCREENS);
        // 現在値をロード
        java.util.Map<String, java.util.Map<String, AuthorityScreenPermission>> matrix = new java.util.HashMap<>();
        for (AuthorityMaster role : roles) {
            var list = permissionMapper.findAllByAuthority(role.getAuthorityCode());
            java.util.Map<String, AuthorityScreenPermission> map = new java.util.HashMap<>();
            for (var p : list) map.put(p.getScreenCode(), p);
            matrix.put(role.getAuthorityCode(), map);
        }
        model.addAttribute("perm", matrix);
        return "permissions/index";
    }

    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SCREEN_PERMISSION)")
    public String update(HttpServletRequest request) {
        List<AuthorityMaster> roles = authorityMasterMapper.selectAll();
        for (AuthorityMaster role : roles) {
            for (String screen : SCREENS) {
                String keyView = role.getAuthorityCode() + "|" + screen + "|view";
                String keyUpd  = role.getAuthorityCode() + "|" + screen + "|update";
                boolean canView = request.getParameter(keyView) != null;
                boolean canUpdate = request.getParameter(keyUpd) != null;
                AuthorityScreenPermission p = new AuthorityScreenPermission();
                p.setAuthorityCode(role.getAuthorityCode());
                p.setScreenCode(screen);
                p.setCanView(canView);
                p.setCanUpdate(canUpdate);
                permissionMapper.upsert(p);
            }
        }
        return "redirect:/permissions";
    }
}

