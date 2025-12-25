package io.github.riemr.shift.application.service;

import io.github.riemr.shift.infrastructure.mapper.*;
import io.github.riemr.shift.infrastructure.persistence.entity.*;
import io.github.riemr.shift.application.dto.DemandIntervalDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.sql.Time;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CsvImportService {

    @Value("${csv.dir:/csv}")
    private Path csvDir;

    private final StoreMapper storeMapper;
    private final RegisterTypeMapper registerTypeMapper;
    private final RegisterMapper registerMapper;
    private final EmployeeMapper employeeMapper;
    private final EmployeeWeeklyPreferenceMapper weeklyPrefMapper;
    private final EmployeeRegisterSkillMapper skillMapper;
    private final RegisterDemandIntervalMapper registerDemandIntervalMapper;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Transactional
    public void importAll(boolean cleanBefore) throws IOException {
        log.info("CSV import start. dir={}", csvDir);
        if (cleanBefore) {
            cleanupAll();
        }
        importStores();
        importRegisterTypes();
        importRegisters();
        importEmployees();
        importWeeklyPreferences();
        importEmployeeSkills();
        importRegisterDemandInterval();
        log.info("CSV import completed.");
    }

    private void cleanupAll() {
        // Delete order: dependents first
        // demand tables: handled by batch cleanup; skip here
        // skills, weekly prefs
        EmployeeRegisterSkillExample se = new EmployeeRegisterSkillExample();
        skillMapper.deleteByExample(se);
        weeklyPrefMapper.deleteAll();
        // employees
        EmployeeExample ee = new EmployeeExample();
        employeeMapper.deleteByExample(ee);
        // registers & types
        registerMapper.deleteAll();
        RegisterTypeExample rte = new RegisterTypeExample();
        registerTypeMapper.deleteByExample(rte);
        // stores last
        StoreExample se2 = new StoreExample();
        storeMapper.deleteByExample(se2);
    }

    private void importStores() throws IOException {
        Path p = csvDir.resolve("store.csv");
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line = br.readLine(); // header skip
            List<Store> list = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                String[] a = line.split(",");
                if (a.length < 3) continue;
                Store s = new Store();
                s.setStoreCode(a[0]);
                s.setStoreName(a[1]);
                s.setTimezone(a[2]);
                list.add(s);
            }
            list.forEach(storeMapper::insertSelective);
            log.info("Imported stores: {}", list.size());
        }
    }

    private void importRegisterTypes() throws IOException {
        Path p = csvDir.resolve("register_type.csv");
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line = br.readLine();
            List<RegisterType> list = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                String[] a = line.split(",");
                if (a.length < 2) continue;
                RegisterType t = new RegisterType();
                t.setTypeCode(a[0]);
                t.setTypeName(a[1]);
                list.add(t);
            }
            list.forEach(registerTypeMapper::insertSelective);
            log.info("Imported register types: {}", list.size());
        }
    }

    private void importRegisters() throws IOException {
        Path p = csvDir.resolve("register.csv");
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line = br.readLine();
            List<Register> list = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                String[] a = line.split(",");
                if (a.length < 6) continue;
                Register r = new Register();
                r.setStoreCode(a[0]);
                r.setRegisterNo(Integer.parseInt(a[1]));
                r.setRegisterName(a[2]);
                r.setShortName(a[3]);
                r.setOpenPriority(Integer.parseInt(a[4]));
                r.setRegisterType(a[5]);
                r.setIsAutoOpenTarget(Boolean.parseBoolean(a[6]));
                r.setMaxAllowance(Integer.parseInt(a[7]));
                list.add(r);
            }
            list.forEach(registerMapper::insertSelective);
            log.info("Imported registers: {}", list.size());
        }
    }

    private void importEmployees() throws IOException {
        Path p = csvDir.resolve("employee.csv");
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line = br.readLine();
            List<Employee> list = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                String[] a = line.split(",");
                if (a.length < 6) continue;
                Employee e = new Employee();
                e.setEmployeeCode(a[0]);
                e.setStoreCode(a[1]);
                e.setEmployeeName(a[2]);
                e.setMinWorkMinutesDay(Integer.parseInt(a[3]));
                e.setMaxWorkMinutesDay(Integer.parseInt(a[4]));
                if (a.length > 5 && a[5] != null && !a[5].isBlank()) {
                    try { e.setMinWorkHoursWeek(Integer.parseInt(a[5])); } catch (NumberFormatException ignore) {}
                }
                if (a.length > 6 && a[6] != null && !a[6].isBlank()) {
                    try { e.setMaxWorkHoursWeek(Integer.parseInt(a[6])); } catch (NumberFormatException ignore) {}
                }
                list.add(e);
            }
            list.forEach(employeeMapper::insertSelective);
            log.info("Imported employees: {}", list.size());
        }

        // Auth fields update pass 2
        try (BufferedReader br2 = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line = br2.readLine();
            int cnt = 0;
            while ((line = br2.readLine()) != null) {
                String[] a = line.split(",");
                if (a.length < 8) continue;
                String code = a[0];
                String raw = a[a.length-2];
                String role = a[a.length-1];
                String hash = encoder.encode((raw == null || raw.isBlank()) ? code : raw);
                employeeMapper.updateAuthFields(code, hash, role);
                cnt++;
            }
            log.info("Updated employee auth fields: {}", cnt);
        }
    }

    private void importWeeklyPreferences() throws IOException {
        Path p = csvDir.resolve("employee_weekly_preference.csv");
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line = br.readLine();
            List<EmployeeWeeklyPreference> list = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                String[] a = line.split(",");
                if (a.length < 5) continue;
                EmployeeWeeklyPreference pref = new EmployeeWeeklyPreference();
                pref.setEmployeeCode(a[0]);
                pref.setDayOfWeek(Short.parseShort(a[1]));
                pref.setWorkStyle(a[2]);
                if (a[3] != null && !a[3].isBlank()) pref.setBaseStartTime(Time.valueOf(LocalTime.parse(a[3])));
                if (a[4] != null && !a[4].isBlank()) pref.setBaseEndTime(Time.valueOf(LocalTime.parse(a[4])));
                if (a.length > 5 && a[5] != null && !a[5].isBlank()) pref.setStoreCode(a[5]);
                list.add(pref);
            }
            list.forEach(weeklyPrefMapper::insert);
            log.info("Imported weekly preferences: {}", list.size());
        }
    }

    private void importEmployeeSkills() throws IOException {
        Path p = csvDir.resolve("employee_register_skill.csv");
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line = br.readLine();
            List<EmployeeRegisterSkill> list = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                String[] a = line.split(",");
                if (a.length < 4) continue;
                EmployeeRegisterSkill s = new EmployeeRegisterSkill();
                s.setStoreCode(a[0]);
                s.setEmployeeCode(a[1]);
                s.setRegisterNo(Integer.parseInt(a[2]));
                s.setSkillLevel(Short.parseShort(a[3]));
                list.add(s);
            }
            list.forEach(skillMapper::insertSelective);
            log.info("Imported skills: {}", list.size());
        }
    }

    private void importRegisterDemandInterval() throws IOException {
        Path p = csvDir.resolve("register_demand_interval.csv");
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line = br.readLine();
            int cnt = 0;
            java.util.Map<String, java.util.List<DemandIntervalDto>> existingByStoreDate = new java.util.HashMap<>();
            while ((line = br.readLine()) != null) {
                String[] a = line.split(",");
                if (a.length < 6) continue;
                DemandIntervalDto dto = DemandIntervalDto.builder()
                        .storeCode(a[0])
                        .targetDate(LocalDate.parse(a[1]))
                        .from(LocalTime.parse(a[2]))
                        .to(LocalTime.parse(a[3]))
                        .demand(Integer.parseInt(a[4]))
                        .registerNo(a.length > 5 && !a[5].isBlank() ? Integer.parseInt(a[5]) : null)
                        .groupId(a.length > 6 && !a[6].isBlank() ? a[6] : null)
                        .build();
                if (dto.getRegisterNo() == null) {
                    var registers = registerMapper.selectByStoreCode(dto.getStoreCode()).stream()
                            .sorted(java.util.Comparator.comparing(Register::getOpenPriority,
                                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                                    .thenComparing(Register::getRegisterNo))
                            .toList();
                    String key = dto.getStoreCode() + "|" + dto.getTargetDate();
                    var existing = existingByStoreDate.computeIfAbsent(key,
                            k -> new java.util.ArrayList<>(
                                    registerDemandIntervalMapper.selectByStoreAndDate(dto.getStoreCode(), dto.getTargetDate())));
                    java.util.Set<Integer> used = new java.util.HashSet<>();
                    for (var e : existing) {
                        if (e.getRegisterNo() == null) continue;
                        if (isOverlap(dto.getFrom(), dto.getTo(), e.getFrom(), e.getTo())) {
                            used.add(e.getRegisterNo());
                        }
                    }
                    int required = dto.getDemand() == null ? 0 : Math.max(0, dto.getDemand());
                    int assigned = 0;
                    for (var reg : registers) {
                        if (assigned >= required) break;
                        if (used.contains(reg.getRegisterNo())) continue;
                        DemandIntervalDto row = DemandIntervalDto.builder()
                                .storeCode(dto.getStoreCode())
                                .targetDate(dto.getTargetDate())
                                .from(dto.getFrom())
                                .to(dto.getTo())
                                .demand(1)
                                .registerNo(reg.getRegisterNo())
                                .build();
                        registerDemandIntervalMapper.upsert(row);
                        existing.add(row);
                        used.add(reg.getRegisterNo());
                        assigned++;
                    }
                } else {
                    registerDemandIntervalMapper.upsert(dto);
                    String key = dto.getStoreCode() + "|" + dto.getTargetDate();
                    var existing = existingByStoreDate.computeIfAbsent(key,
                            k -> new java.util.ArrayList<>(
                                    registerDemandIntervalMapper.selectByStoreAndDate(dto.getStoreCode(), dto.getTargetDate())));
                    existing.add(dto);
                }
                cnt++;
            }
            log.info("Imported register_demand_interval: {}", cnt);
        }
    }

    private boolean isOverlap(LocalTime aFrom, LocalTime aTo, LocalTime bFrom, LocalTime bTo) {
        if (aFrom == null || aTo == null || bFrom == null || bTo == null) return false;
        return aFrom.isBefore(bTo) && bFrom.isBefore(aTo);
    }

    // ===== UI: per-file upsert imports =====
    @Transactional
    public int upsertStoresCsv(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            int cnt = 0;
            int lineNo = 1; // header is line 1
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                try {
                    String[] a = line.split(",");
                    if (a.length < 3) continue;
                    Store s = new Store();
                    s.setStoreCode(a[0]);
                    s.setStoreName(a[1]);
                    s.setTimezone(a[2]);
                    storeMapper.upsert(s);
                    cnt++;
                } catch (Exception ex) {
                    throw new IllegalArgumentException("CSV parse error at line " + lineNo + ": " + ex.getMessage(), ex);
                }
            }
            return cnt;
        }
    }

    @Transactional
    public int upsertRegisterTypesCsv(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            int cnt = 0;
            int lineNo = 1;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                try {
                    String[] a = line.split(",");
                    if (a.length < 2) continue;
                    RegisterType t = new RegisterType();
                    t.setTypeCode(a[0]);
                    t.setTypeName(a[1]);
                    registerTypeMapper.upsert(t);
                    cnt++;
                } catch (Exception ex) {
                    throw new IllegalArgumentException("CSV parse error at line " + lineNo + ": " + ex.getMessage(), ex);
                }
            }
            return cnt;
        }
    }

    @Transactional
    public int upsertRegistersCsv(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            int cnt = 0;
            int lineNo = 1;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                try {
                    String[] a = line.split(",");
                    if (a.length < 6) continue;
                    Register r = new Register();
                    r.setStoreCode(a[0]);
                    r.setRegisterNo(Integer.parseInt(a[1]));
                    r.setRegisterName(a[2]);
                    r.setShortName(a[3]);
                    r.setOpenPriority(Integer.parseInt(a[4]));
                    r.setRegisterType(a[5]);
                    r.setIsAutoOpenTarget(Boolean.parseBoolean(a[6]));
                    r.setMaxAllowance(Integer.parseInt(a[7]));
                    registerMapper.upsert(r);
                    cnt++;
                } catch (Exception ex) {
                    throw new IllegalArgumentException("CSV parse error at line " + lineNo + ": " + ex.getMessage(), ex);
                }
            }
            return cnt;
        }
    }

    @Transactional
    public int upsertEmployeesCsv(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            int cnt = 0;
            int lineNo = 1;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                try {
                    String[] a = line.split(",");
                    if (a.length < 6) continue;
                    Employee e = new Employee();
                    e.setEmployeeCode(a[0]);
                    e.setStoreCode(a[1]);
                    e.setEmployeeName(a[2]);
                    e.setMinWorkMinutesDay(Integer.parseInt(a[3]));
                    e.setMaxWorkMinutesDay(Integer.parseInt(a[4]));
                    if (a.length > 5 && a[5] != null && !a[5].isBlank()) {
                        try { e.setMinWorkHoursWeek(Integer.parseInt(a[5])); } catch (NumberFormatException ignore) {}
                    }
                    if (a.length > 6 && a[6] != null && !a[6].isBlank()) {
                        try { e.setMaxWorkHoursWeek(Integer.parseInt(a[6])); } catch (NumberFormatException ignore) {}
                    }
                    employeeMapper.upsert(e);
                    cnt++;
                } catch (Exception ex) {
                    throw new IllegalArgumentException("CSV parse error at line " + lineNo + ": " + ex.getMessage(), ex);
                }
            }
            return cnt;
        }
    }

    @Transactional
    public int upsertWeeklyPreferencesCsv(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            int cnt = 0;
            int lineNo = 1;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                try {
                    String[] a = line.split(",");
                    if (a.length < 5) continue;
                    EmployeeWeeklyPreference pref = new EmployeeWeeklyPreference();
                    pref.setEmployeeCode(a[0]);
                    pref.setDayOfWeek(Short.parseShort(a[1]));
                    pref.setWorkStyle(a[2]);
                    if (a[3] != null && !a[3].isBlank()) pref.setBaseStartTime(Time.valueOf(LocalTime.parse(a[3])));
                    if (a[4] != null && !a[4].isBlank()) pref.setBaseEndTime(Time.valueOf(LocalTime.parse(a[4])));
                    if (a.length > 5 && a[5] != null && !a[5].isBlank()) pref.setStoreCode(a[5]);
                    weeklyPrefMapper.upsert(pref);
                    cnt++;
                } catch (Exception ex) {
                    throw new IllegalArgumentException("CSV parse error at line " + lineNo + ": " + ex.getMessage(), ex);
                }
            }
            return cnt;
        }
    }

    @Transactional
    public int upsertEmployeeSkillsCsv(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            int cnt = 0;
            int lineNo = 1;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                try {
                    String[] a = line.split(",");
                    if (a.length < 4) continue;
                    EmployeeRegisterSkill s = new EmployeeRegisterSkill();
                    s.setStoreCode(a[0]);
                    s.setEmployeeCode(a[1]);
                    s.setRegisterNo(Integer.parseInt(a[2]));
                    s.setSkillLevel(Short.parseShort(a[3]));
                    skillMapper.upsert(s);
                    cnt++;
                } catch (Exception ex) {
                    throw new IllegalArgumentException("CSV parse error at line " + lineNo + ": " + ex.getMessage(), ex);
                }
            }
            return cnt;
        }
    }

    @Transactional
    public int upsertRegisterDemandIntervalCsv(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            int cnt = 0;
            int lineNo = 1;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                try {
                    String[] a = line.split(",");
                    if (a.length < 5) continue;
                    DemandIntervalDto dto = DemandIntervalDto.builder()
                            .storeCode(a[0])
                            .targetDate(LocalDate.parse(a[1]))
                            .from(LocalTime.parse(a[2]))
                            .to(LocalTime.parse(a[3]))
                            .demand(Integer.parseInt(a[4]))
                            .taskCode(a.length > 5 && !a[5].isBlank() ? a[5] : null)
                            .groupId(a.length > 6 && !a[6].isBlank() ? a[6] : null)
                            .build();
                    registerDemandIntervalMapper.upsert(dto);
                    cnt++;
                } catch (Exception ex) {
                    throw new IllegalArgumentException("CSV parse error at line " + lineNo + ": " + ex.getMessage(), ex);
                }
            }
            return cnt;
        }
    }
}
