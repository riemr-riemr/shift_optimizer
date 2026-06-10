package io.github.riemr.shift.presentation.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;

import io.github.riemr.shift.application.service.CsvImportService;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Controller
@RequestMapping("/csv-import")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "shift.csv-import.enabled", havingValue = "true", matchIfMissing = true)
public class CsvImportController {
    private static final Map<String, String> TEMPLATE_FILES = Map.of(
            "store", "store.csv",
            "register-type", "register_type.csv",
            "register", "register.csv",
            "employee", "employee.csv",
            "employee-weekly-preference", "employee_weekly_preference.csv",
            "employee-register-skill", "employee_register_skill.csv",
            "register-demand-interval", "register_demand_interval.csv");

    private final JobLauncher jobLauncher;
    private final Job masterImportJob;
    private final CsvImportService csvImportService;

    @GetMapping
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).CSV_IMPORT)")
    public String view() {
        return "csv/import";
    }

    @GetMapping("/template/{templateType}")
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).CSV_IMPORT)")
    public ResponseEntity<Resource> downloadTemplate(@PathVariable String templateType) {
        String fileName = TEMPLATE_FILES.get(templateType);
        if (fileName == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "テンプレートが見つかりません");
        }
        ClassPathResource resource = new ClassPathResource("csv-template/" + fileName);
        if (!resource.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "テンプレートが見つかりません");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(resource);
    }

    @PostMapping
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).CSV_IMPORT)")
    public String run(@RequestParam(name = "clean", defaultValue = "true") boolean clean,
                      Model model) {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addLong("ts", System.currentTimeMillis())
                    .addString("clean", Boolean.toString(clean))
                    .toJobParameters();
            jobLauncher.run(masterImportJob, params);
            model.addAttribute("message", "CSV取り込みが完了しました");
            model.addAttribute("success", true);
        } catch (Exception e) {
            model.addAttribute("message", "取り込みに失敗しました: " + e.getMessage());
            model.addAttribute("success", false);
        }
        return "csv/import";
    }

    // ===== UI: per-file upsert endpoints =====
    @PostMapping("/store")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).CSV_IMPORT)")
    public String uploadStore(@RequestParam("file") MultipartFile file, Model model) {
        try {
            int cnt = csvImportService.upsertStoresCsv(file.getInputStream());
            model.addAttribute("message", "店舗CSVを取り込みました: " + cnt + "件");
            model.addAttribute("success", true);
        } catch (Exception e) {
            model.addAttribute("message", "店舗CSV取り込みに失敗しました: " + e.getMessage());
            model.addAttribute("success", false);
        }
        return "csv/import";
    }

    @PostMapping("/register-type")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).CSV_IMPORT)")
    public String uploadRegisterType(@RequestParam("file") MultipartFile file, Model model) {
        try {
            int cnt = csvImportService.upsertRegisterTypesCsv(file.getInputStream());
            model.addAttribute("message", "レジ種別CSVを取り込みました: " + cnt + "件");
            model.addAttribute("success", true);
        } catch (Exception e) {
            model.addAttribute("message", "レジ種別CSV取り込みに失敗しました: " + e.getMessage());
            model.addAttribute("success", false);
        }
        return "csv/import";
    }

    @PostMapping("/register")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).CSV_IMPORT)")
    public String uploadRegister(@RequestParam("file") MultipartFile file, Model model) {
        try {
            int cnt = csvImportService.upsertRegistersCsv(file.getInputStream());
            model.addAttribute("message", "レジCSVを取り込みました: " + cnt + "件");
            model.addAttribute("success", true);
        } catch (Exception e) {
            model.addAttribute("message", "レジCSV取り込みに失敗しました: " + e.getMessage());
            model.addAttribute("success", false);
        }
        return "csv/import";
    }

    @PostMapping("/employee")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).CSV_IMPORT)")
    public String uploadEmployee(@RequestParam("file") MultipartFile file, Model model) {
        try {
            int cnt = csvImportService.upsertEmployeesCsv(file.getInputStream());
            model.addAttribute("message", "従業員CSVを取り込みました: " + cnt + "件");
            model.addAttribute("success", true);
        } catch (Exception e) {
            model.addAttribute("message", "従業員CSV取り込みに失敗しました: " + e.getMessage());
            model.addAttribute("success", false);
        }
        return "csv/import";
    }

    @PostMapping("/employee-weekly-preference")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).CSV_IMPORT)")
    public String uploadWeeklyPref(@RequestParam("file") MultipartFile file, Model model) {
        try {
            int cnt = csvImportService.upsertWeeklyPreferencesCsv(file.getInputStream());
            model.addAttribute("message", "従業員曜日別希望CSVを取り込みました: " + cnt + "件");
            model.addAttribute("success", true);
        } catch (Exception e) {
            model.addAttribute("message", "従業員曜日別希望CSV取り込みに失敗しました: " + e.getMessage());
            model.addAttribute("success", false);
        }
        return "csv/import";
    }

    @PostMapping("/employee-register-skill")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).CSV_IMPORT)")
    public String uploadSkill(@RequestParam("file") MultipartFile file, Model model) {
        try {
            int cnt = csvImportService.upsertEmployeeSkillsCsv(file.getInputStream());
            model.addAttribute("message", "従業員レジスキルCSVを取り込みました: " + cnt + "件");
            model.addAttribute("success", true);
        } catch (Exception e) {
            model.addAttribute("message", "従業員レジスキルCSV取り込みに失敗しました: " + e.getMessage());
            model.addAttribute("success", false);
        }
        return "csv/import";
    }

    @PostMapping("/register-demand-interval")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).CSV_IMPORT)")
    public String uploadDemandInterval(@RequestParam("file") MultipartFile file, Model model) {
        try {
            int cnt = csvImportService.upsertRegisterDemandIntervalCsv(file.getInputStream());
            model.addAttribute("message", "需要(区間[from,to])CSVを取り込みました: " + cnt + "件");
            model.addAttribute("success", true);
        } catch (Exception e) {
            model.addAttribute("message", "需要(区間)CSV取り込みに失敗しました: " + e.getMessage());
            model.addAttribute("success", false);
        }
        return "csv/import";
    }
}
