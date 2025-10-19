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
import io.github.riemr.shift.infrastructure.mapper.StoreMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.Store;
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
    private final io.github.riemr.shift.application.service.AppSettingService appSettingService;
    private final StoreMapper storeMapper;
    private final RegisterMapper registerMapper;

    /**
     * 編集画面を表示 (GET)。
     */
    @GetMapping
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).REGISTER_DEMAND)")
    public String show(@RequestParam(name = "date", required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       @RequestParam(name = "storeCode", required = false) String storeCode,
                       Model model) {
        LocalDate target = (date == null ? LocalDate.now() : date);

        List<Store> stores = storeMapper.selectByExample(null);
        stores.sort(java.util.Comparator.comparing(Store::getStoreCode));
        String effectiveStore = (storeCode != null && !storeCode.isBlank())
                ? storeCode
                : (stores.isEmpty() ? null : stores.get(0).getStoreCode());

        int res = appSettingService.getTimeResolutionMinutes();
        int slotCount = 1440 / res;
        int[] quarterDemands = effectiveStore == null ? new int[slotCount] : service.getQuarterDemands(effectiveStore, target, res);
        var registers = (effectiveStore == null) ? java.util.List.<io.github.riemr.shift.infrastructure.persistence.entity.Register>of()
                : registerMapper.selectByStoreCode(effectiveStore);
        registers.sort(java.util.Comparator.comparing(io.github.riemr.shift.infrastructure.persistence.entity.Register::getRegisterNo));

        RegisterDemandHourForm form = new RegisterDemandHourForm(effectiveStore, target, java.util.Collections.emptyList());
        model.addAttribute("command", form);
        model.addAttribute("stores", stores);
        model.addAttribute("selectedStoreCode", effectiveStore);
        model.addAttribute("registers", registers);
        model.addAttribute("quarterDemands", java.util.Arrays.stream(quarterDemands).boxed().toList());
        model.addAttribute("timeResolutionMinutes", res);
        model.addAttribute("slotCount", slotCount);
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
        // Accept interval grid via demandsCsv
        String demandsCsv = null;
        try {
            demandsCsv = (String) ((org.springframework.web.context.request.ServletRequestAttributes)
                    org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes())
                    .getRequest().getParameter("demandsCsv");
        } catch (Exception ignore) {}
        if (demandsCsv != null && !demandsCsv.isBlank()) {
            java.util.List<Integer> slots = java.util.Arrays.stream(demandsCsv.split(","))
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
