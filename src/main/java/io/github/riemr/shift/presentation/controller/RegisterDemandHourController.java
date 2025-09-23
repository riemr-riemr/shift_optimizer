package io.github.riemr.shift.presentation.controller;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import io.github.riemr.shift.application.dto.RegisterDemandHourDto;
import io.github.riemr.shift.application.service.RegisterDemandHourService;
import io.github.riemr.shift.presentation.form.RegisterDemandHourForm;
import lombok.RequiredArgsConstructor;

/**
 * MVC Controller exposing a simple screen to edit hourly register demand.
 */
@Controller
@RequestMapping("/register-demand")
@RequiredArgsConstructor
class RegisterDemandHourController {

    private final RegisterDemandHourService service;
    // TODO ひとまず固定店舗。将来的にはログイン情報から取得
    private static final String STORE_CODE = "569";

    /**
     * 編集画面を表示 (GET)。
     */
    @GetMapping
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).REGISTER_DEMAND)")
    public String show(@RequestParam(name = "date", required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       Model model) {
        LocalDate target = (date == null ? LocalDate.now() : date);
        List<RegisterDemandHourDto> hours = service.findHourlyDemands(STORE_CODE, target);
        if (hours.isEmpty()) {
            hours = IntStream.range(0, 24)
                         .mapToObj(h -> new RegisterDemandHourDto(STORE_CODE, target, LocalTime.of(h,0), 0))
                         .toList();

        }
        RegisterDemandHourForm form = new RegisterDemandHourForm(STORE_CODE, target, hours);
        model.addAttribute("command", form);
        return "registerDemand/form";
    }

    /**
     * 保存 (POST)。
     */
    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).REGISTER_DEMAND)")
    public String save(@ModelAttribute("command") RegisterDemandHourForm form,
                       BindingResult br, RedirectAttributes redirect) {
        if (br.hasErrors()) {
            return "registerDemand/form";
        }
        service.saveHourlyDemands(form.getStoreCode(), form.getTargetDate(), form.getHours());

        redirect.addAttribute("date", form.getTargetDate().format(DateTimeFormatter.ISO_DATE));
        return "redirect:/register-demand";
    }
}
