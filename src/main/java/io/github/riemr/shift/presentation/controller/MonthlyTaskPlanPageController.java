package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.service.DepartmentSkillMatrixService;
import io.github.riemr.shift.application.service.TaskMasterService;
import io.github.riemr.shift.application.service.TaskCategoryMasterService;
import io.github.riemr.shift.infrastructure.mapper.StoreMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/tasks/monthly")
public class MonthlyTaskPlanPageController {
    private final StoreMapper storeMapper;
    private final DepartmentSkillMatrixService departmentSkillMatrixService;
    private final TaskMasterService taskMasterService;
    private final TaskCategoryMasterService taskCategoryMasterService;

    public MonthlyTaskPlanPageController(StoreMapper storeMapper,
                                         DepartmentSkillMatrixService departmentSkillMatrixService,
                                         TaskMasterService taskMasterService,
                                         TaskCategoryMasterService taskCategoryMasterService) {
        this.storeMapper = storeMapper;
        this.departmentSkillMatrixService = departmentSkillMatrixService;
        this.taskMasterService = taskMasterService;
        this.taskCategoryMasterService = taskCategoryMasterService;
    }

    @GetMapping
    public String index(@RequestParam(name = "store", required = false) String storeCode,
                        @RequestParam(name = "dept", required = false) String departmentCode,
                        Model model) {
        model.addAttribute("storeCode", storeCode);
        model.addAttribute("dept", departmentCode);
        try {
            io.github.riemr.shift.application.service.AppSettingService svc =
                    org.springframework.web.context.ContextLoader.getCurrentWebApplicationContext()
                            .getBean(io.github.riemr.shift.application.service.AppSettingService.class);
            model.addAttribute("timeResolutionMinutes", svc.getTimeResolutionMinutes());
        } catch (Exception ignored) { model.addAttribute("timeResolutionMinutes", 15); }
        model.addAttribute("stores", storeMapper.selectByExample(null));
        model.addAttribute("departments", departmentSkillMatrixService.listDepartments());
        model.addAttribute("masters", taskMasterService.list());
        model.addAttribute("categories", taskCategoryMasterService.list());
        return "tasks/monthly";
    }
}
