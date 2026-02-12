package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.infrastructure.mapper.AuthorityMasterMapper;
import io.github.riemr.shift.infrastructure.mapper.AuthorityScreenPermissionMapper;
import io.github.riemr.shift.infrastructure.mapper.AuthorityDepartmentPermissionMapper;
import io.github.riemr.shift.infrastructure.mapper.DepartmentMasterMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.AuthorityMaster;
import io.github.riemr.shift.infrastructure.persistence.entity.AuthorityDepartmentPermission;
import io.github.riemr.shift.infrastructure.persistence.entity.AuthorityScreenPermission;
import io.github.riemr.shift.infrastructure.persistence.entity.DepartmentMaster;
import io.github.riemr.shift.util.ScreenCodes;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final AuthorityMasterMapper authorityMasterMapper;
    private final AuthorityScreenPermissionMapper permissionMapper;
    private final DepartmentMasterMapper departmentMasterMapper;
    private final AuthorityDepartmentPermissionMapper authorityDepartmentPermissionMapper;

    private static final List<String> SCREENS = List.of(
            ScreenCodes.SHIFT_MONTHLY,
            ScreenCodes.SHIFT_DAILY,
            ScreenCodes.EMPLOYEE_LIST,
            ScreenCodes.EMPLOYEE_SHIFT,
            ScreenCodes.EMPLOYEE_REQUEST,
            ScreenCodes.SKILL_MATRIX,
            ScreenCodes.DEPT_SKILL_MATRIX,
            ScreenCodes.REGISTER_DEMAND,
            ScreenCodes.STAFFING_BALANCE,
            ScreenCodes.TASK_MASTER,
            ScreenCodes.TASK_PLAN,
            ScreenCodes.SETTINGS,
            ScreenCodes.CSV_IMPORT,
            ScreenCodes.SCREEN_PERMISSION
    );
    private static final Pattern AUTHORITY_CODE_PATTERN = Pattern.compile("^[A-Z0-9_]{2,20}$");
    private static final int AUTHORITY_NAME_MAX_LENGTH = 50;

    @GetMapping
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).SCREEN_PERMISSION)")
    public String view(Model model) {
        populatePermissionPage(model);
        return "permissions/index";
    }

    @PostMapping("/roles/create")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SCREEN_PERMISSION)")
    public String createRole(@RequestParam("authorityCode") String authorityCode,
                             @RequestParam("authorityName") String authorityName,
                             @RequestParam(name = "description", required = false) String description,
                             RedirectAttributes redirectAttributes) {
        String normalizedCode = normalizeCode(authorityCode);
        String normalizedName = trimToNull(authorityName);
        String normalizedDesc = normalizeDescription(description);

        if (normalizedCode == null || !AUTHORITY_CODE_PATTERN.matcher(normalizedCode).matches()) {
            redirectAttributes.addFlashAttribute("error", "権限コードは2〜20文字の英大文字・数字・_で入力してください。");
            return "redirect:/permissions";
        }
        if (normalizedName == null) {
            redirectAttributes.addFlashAttribute("error", "権限名を入力してください。");
            return "redirect:/permissions";
        }
        if (normalizedName.length() > AUTHORITY_NAME_MAX_LENGTH) {
            redirectAttributes.addFlashAttribute("error", "権限名は50文字以内で入力してください。");
            return "redirect:/permissions";
        }
        if (authorityMasterMapper.findByCode(normalizedCode) != null) {
            redirectAttributes.addFlashAttribute("error", "同じ権限コードが既に存在します。");
            return "redirect:/permissions";
        }

        AuthorityMaster newRole = new AuthorityMaster();
        newRole.setAuthorityCode(normalizedCode);
        newRole.setAuthorityName(normalizedName);
        newRole.setDescription(normalizedDesc);
        authorityMasterMapper.insert(newRole);

        initializeScreenPermissions(normalizedCode);

        redirectAttributes.addFlashAttribute("success", "権限マスタを作成しました。");
        return "redirect:/permissions";
    }

    @PostMapping("/roles/update")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SCREEN_PERMISSION)")
    public String updateRoles(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        List<AuthorityMaster> roles = authorityMasterMapper.selectAll();
        for (AuthorityMaster role : roles) {
            String code = role.getAuthorityCode();
            String nameKey = code + "|name";
            String descriptionKey = code + "|description";
            String normalizedName = trimToNull(request.getParameter(nameKey));
            String normalizedDesc = normalizeDescription(request.getParameter(descriptionKey));

            if (normalizedName == null) {
                redirectAttributes.addFlashAttribute("error", "権限名を入力してください: " + code);
                return "redirect:/permissions";
            }
            if (normalizedName.length() > AUTHORITY_NAME_MAX_LENGTH) {
                redirectAttributes.addFlashAttribute("error", "権限名は50文字以内で入力してください: " + code);
                return "redirect:/permissions";
            }

            AuthorityMaster updated = new AuthorityMaster();
            updated.setAuthorityCode(code);
            updated.setAuthorityName(normalizedName);
            updated.setDescription(normalizedDesc);
            authorityMasterMapper.update(updated);
        }

        redirectAttributes.addFlashAttribute("success", "権限マスタを更新しました。");
        return "redirect:/permissions";
    }

    @PostMapping
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SCREEN_PERMISSION)")
    public String update(HttpServletRequest request, RedirectAttributes redirectAttributes) {
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

            authorityDepartmentPermissionMapper.deleteByAuthority(role.getAuthorityCode());
            List<DepartmentMaster> departments = departmentMasterMapper.selectAll();
            for (DepartmentMaster department : departments) {
                String deptKey = role.getAuthorityCode() + "|DEPT|" + department.getDepartmentCode();
                if (request.getParameter(deptKey) == null) {
                    continue;
                }
                AuthorityDepartmentPermission deptPerm = new AuthorityDepartmentPermission();
                deptPerm.setAuthorityCode(role.getAuthorityCode());
                deptPerm.setDepartmentCode(department.getDepartmentCode());
                authorityDepartmentPermissionMapper.insert(deptPerm);
            }
        }
        redirectAttributes.addFlashAttribute("success", "権限設定を保存しました。");
        return "redirect:/permissions";
    }

    private void populatePermissionPage(Model model) {
        List<AuthorityMaster> roles = authorityMasterMapper.selectAll();
        List<DepartmentMaster> departments = departmentMasterMapper.selectAll();
        model.addAttribute("roles", roles);
        model.addAttribute("screens", SCREENS);
        model.addAttribute("departments", departments);

        Map<String, Map<String, AuthorityScreenPermission>> matrix = new HashMap<>();
        for (AuthorityMaster role : roles) {
            var list = permissionMapper.findAllByAuthority(role.getAuthorityCode());
            Map<String, AuthorityScreenPermission> map = new HashMap<>();
            for (var p : list) {
                map.put(p.getScreenCode(), p);
            }
            matrix.put(role.getAuthorityCode(), map);
        }
        model.addAttribute("perm", matrix);

        Map<String, Map<String, Boolean>> deptMatrix = new HashMap<>();
        for (AuthorityMaster role : roles) {
            var list = authorityDepartmentPermissionMapper.findDepartmentCodesByAuthority(role.getAuthorityCode());
            Map<String, Boolean> m = new HashMap<>();
            for (String departmentCode : list) {
                m.put(departmentCode, true);
            }
            deptMatrix.put(role.getAuthorityCode(), m);
        }
        model.addAttribute("deptPerm", deptMatrix);
    }

    private void initializeScreenPermissions(String authorityCode) {
        for (String screen : SCREENS) {
            AuthorityScreenPermission p = new AuthorityScreenPermission();
            p.setAuthorityCode(authorityCode);
            p.setScreenCode(screen);
            p.setCanView(false);
            p.setCanUpdate(false);
            permissionMapper.upsert(p);
        }
    }

    private String normalizeCode(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        return trimmed.toUpperCase();
    }

    private String normalizeDescription(String value) {
        return trimToNull(value);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed;
    }
}
