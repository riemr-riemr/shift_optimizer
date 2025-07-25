package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.service.EmployeeService;
import io.github.riemr.shift.presentation.form.EmployeeForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/employees")
@RequiredArgsConstructor
public class EmployeeController {
    private final EmployeeService service;

    /* 一覧 */
    @GetMapping
    public String list(Model model) {
        model.addAttribute("employees", service.findAll());
        return "employee/list";
    }

    /* 新規フォーム */
    @GetMapping("/new")
    public String create(Model model) {
        model.addAttribute("employeeForm", new EmployeeForm());
        model.addAttribute("edit", false);
        return "employee/form";
    }

    /* 編集フォーム */
    @GetMapping("/{code}")
    public String edit(@PathVariable String code, Model model) {
        model.addAttribute("employeeForm", EmployeeForm.from(service.find(code)));
        model.addAttribute("edit", true);
        return "employee/form";
    }

    /* 保存 */
    @PostMapping
    public String save(@Valid @ModelAttribute("employeeForm") EmployeeForm form,
                       BindingResult result, @RequestParam("edit") boolean edit) {
        if (result.hasErrors()) {
            return "employee/form";
        }
        service.save(form.toEntity(), !edit);
        return "redirect:/employees";
    }

    /* 削除 */
    @PostMapping("/{code}/delete")
    public String delete(@PathVariable String code) {
        service.delete(code);
        return "redirect:/employees";
    }
}