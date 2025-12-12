package io.github.riemr.shift.presentation.controller;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Comparator;
import java.util.Collections;
import java.util.Arrays;

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
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;

import io.github.riemr.shift.application.service.RegisterDemandHourService;
import io.github.riemr.shift.application.service.AppSettingService;
import io.github.riemr.shift.presentation.form.RegisterDemandHourForm;
import io.github.riemr.shift.infrastructure.mapper.StoreMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.Store;
import io.github.riemr.shift.infrastructure.persistence.entity.Register;
import io.github.riemr.shift.infrastructure.mapper.RegisterMapper;

import lombok.RequiredArgsConstructor;

/**
 * MVC Controller exposing a simple screen to edit hourly register demand.
 */
@Controller
@RequestMapping("/register-demand")
@RequiredArgsConstructor
class RegisterDemandHourController {

    private final RegisterDemandHourService service;
    private final AppSettingService appSettingService;
    private final StoreMapper storeMapper;
    private final RegisterMapper registerMapper;

    /**
     * 編集画面を表示 (GET)。
     */
    @GetMapping
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).REGISTER_DEMAND)")
    public String show(@RequestParam(name = "date", required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       @RequestParam(name = "storeCode", required = false) String storeCode,
                       Model model) {
        LocalDate target = (date == null ? LocalDate.now() : date);

        List<Store> stores = storeMapper.selectByExample(null);
        stores.sort(Comparator.comparing(Store::getStoreCode));
        String effectiveStore = (storeCode != null && !storeCode.isBlank())
                ? storeCode
                : (stores.isEmpty() ? null : stores.get(0).getStoreCode());

        int res = appSettingService.getTimeResolutionMinutes();
        int slotCount = 1440 / res;
        int[] quarterDemands = effectiveStore == null ? new int[slotCount] : service.getQuarterDemands(effectiveStore, target, res);
        var registers = (effectiveStore == null) ? List.<Register>of()
                : registerMapper.selectByStoreCode(effectiveStore);
        registers.sort(Comparator.comparing(Register::getRegisterNo));

        RegisterDemandHourForm form = new RegisterDemandHourForm(effectiveStore, target, Collections.emptyList());
        model.addAttribute("command", form);
        model.addAttribute("stores", stores);
        model.addAttribute("selectedStoreCode", effectiveStore);
        model.addAttribute("registers", registers);
        model.addAttribute("quarterDemands", Arrays.stream(quarterDemands).boxed().toList());
        model.addAttribute("timeResolutionMinutes", res);
        model.addAttribute("slotCount", slotCount);
        return "registerDemand/form";
    }

    /**
     * 保存 (POST)。
     */
    @PostMapping
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).REGISTER_DEMAND)")
    public String save(@ModelAttribute("command") RegisterDemandHourForm form,
                       BindingResult br, RedirectAttributes redirect) {
        if (br.hasErrors()) {
            return "registerDemand/form";
        }
        // Accept interval grid via demandsCsv
        String demandsCsv = null;
        try {
            demandsCsv = (String) ((ServletRequestAttributes)
                    RequestContextHolder.currentRequestAttributes())
                    .getRequest().getParameter("demandsCsv");
        } catch (Exception ignore) {}
        if (demandsCsv != null && !demandsCsv.isBlank()) {
            List<Integer> slots = Arrays.stream(demandsCsv.split(","))
                    .filter(s -> !s.isBlank())
                    .map(Integer::parseInt)
                    .toList();
            service.saveQuarterDemands(form.getStoreCode(), form.getTargetDate(), slots);
        } else {
            // legacy hourly form
            service.saveHourlyDemands(form.getStoreCode(), form.getTargetDate(), form.getHours());
        }

        redirect.addAttribute("date", form.getTargetDate().format(DateTimeFormatter.ISO_DATE));
        redirect.addAttribute("storeCode", form.getStoreCode());
        return "redirect:/register-demand";
    }
}
