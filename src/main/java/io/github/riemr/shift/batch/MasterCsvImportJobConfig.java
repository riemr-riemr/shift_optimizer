package io.github.riemr.shift.batch;

import io.github.riemr.shift.infrastructure.persistence.entity.*;
import io.github.riemr.shift.infrastructure.mapper.*;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.time.ZoneId;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.batch.MyBatisBatchItemWriter;
import org.springframework.batch.core.*;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer; // run.id を自動採番 :contentReference[oaicite:2]{index=2}
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.mapping.FieldSetMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.propertyeditors.CustomDateEditor;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@RequiredArgsConstructor
public class MasterCsvImportJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager txManager;
    private final SqlSessionFactory sqlSessionFactory;
    private final io.github.riemr.shift.infrastructure.mapper.EmployeeWeeklyPreferenceMapper weeklyPrefMapper;
    private final RegisterDemandQuarterMapper demandMapper;
    private final WorkDemandQuarterMapper workDemandQuarterMapper;
    private final EmployeeRegisterSkillMapper skillMapper;
    private final EmployeeMapper employeeMapper;
    private final RegisterMapper registerMapper;
    private final RegisterTypeMapper registerTypeMapper;
    private final StoreMapper storeMapper;
    private final DepartmentMasterMapper departmentMasterMapper;
    private final StoreDepartmentMapper storeDepartmentMapper;
    private final EmployeeDepartmentMapper employeeDepartmentMapper;
    private final EmployeeDepartmentSkillMapper employeeDepartmentSkillMapper;
    private final io.github.riemr.shift.infrastructure.mapper.EmployeeTaskSkillMapper employeeTaskSkillMapper;

    /* === Job =========================================================== */
    @Bean
    public Job masterImportJob(Step cleanupStep,
            Step storeStep,
            Step registerTypeStep,
            Step registerStep,
            Step employeeStep,
            Step employeeAuthStep,
            Step employeeWeeklyPreferenceStep,
            Step employeeRegisterSkillStep,
            Step departmentMasterStep,
            Step storeDepartmentStep,
            Step employeeDepartmentStep,
            Step employeeDepartmentSkillStep,
            Step registerDemandQuarterStep,
            Step workDemandQuarterStep,
            Step employeeTaskSkillStep) {
        return new JobBuilder("masterImportJob", jobRepository)
                .incrementer(new RunIdIncrementer()) // run.id を自動付与
                .start(cleanupStep)
                .next(storeStep)
                .next(registerTypeStep)
                .next(registerStep)
                .next(departmentMasterStep)
                .next(storeDepartmentStep)
                .next(employeeStep)
                .next(employeeAuthStep)
                .next(employeeDepartmentStep)
                .next(employeeDepartmentSkillStep)
                .next(employeeWeeklyPreferenceStep)
                .next(employeeRegisterSkillStep)
                .next(employeeTaskSkillStep)
                .next(registerDemandQuarterStep)
                .next(workDemandQuarterStep)
                .build();
    }

    /* === Step : cleanup (optional by job parameter 'clean') ======== */
    @Bean
    public Step cleanupStep() {
        return new StepBuilder("cleanupStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    Map<String, Object> params = chunkContext.getStepContext().getJobParameters();
                    Object cleanParam = params != null ? params.get("clean") : null;
                    boolean doClean = cleanParam != null && Boolean.parseBoolean(cleanParam.toString());
                    if (!doClean) {
                        return RepeatStatus.FINISHED;
                    }
                    // dependents first
                    demandMapper.deleteByExample(new RegisterDemandQuarterExample());
                    skillMapper.deleteByExample(new EmployeeRegisterSkillExample());
                    weeklyPrefMapper.deleteAll();
                    employeeMapper.deleteByExample(new EmployeeExample());
                    registerMapper.deleteAll();
                    registerTypeMapper.deleteByExample(new RegisterTypeExample());
                    storeMapper.deleteByExample(new StoreExample());
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }

    /* === Step : department_master.csv ============================================= */
    @Bean
    public Step departmentMasterStep(FlatFileItemReader<DepartmentMaster> departmentMasterReader) {
        return new StepBuilder("departmentMasterStep", jobRepository)
                .<DepartmentMaster, DepartmentMaster>chunk(1000, txManager)
                .reader(departmentMasterReader)
                .writer(myBatisWriter("io.github.riemr.shift.infrastructure.mapper.DepartmentMasterMapper.insert"))
                .build();
    }

    @Bean
    public FlatFileItemReader<DepartmentMaster> departmentMasterReader(@Value("${csv.dir:/csv}") Path csvDir) {
        return csvReader(csvDir.resolve("department_master.csv"),
                new String[] { "departmentCode", "departmentName", "displayOrder", "isActive", "isRegister" },
                DepartmentMaster.class);
    }

    /* === Step : store_department.csv ============================================= */
    @Bean
    public Step storeDepartmentStep(FlatFileItemReader<StoreDepartment> storeDepartmentReader) {
        return new StepBuilder("storeDepartmentStep", jobRepository)
                .<StoreDepartment, StoreDepartment>chunk(1000, txManager)
                .reader(storeDepartmentReader)
                .writer(myBatisWriter("io.github.riemr.shift.infrastructure.mapper.StoreDepartmentMapper.insert"))
                .build();
    }

    @Bean
    public FlatFileItemReader<StoreDepartment> storeDepartmentReader(@Value("${csv.dir:/csv}") Path csvDir) {
        return csvReader(csvDir.resolve("store_department.csv"),
                new String[] { "storeCode", "departmentCode", "displayOrder", "isActive" },
                StoreDepartment.class);
    }

    /* === Step : employee_department.csv ============================================= */
    @Bean
    public Step employeeDepartmentStep(FlatFileItemReader<EmployeeDepartment> employeeDepartmentReader) {
        return new StepBuilder("employeeDepartmentStep", jobRepository)
                .<EmployeeDepartment, EmployeeDepartment>chunk(1000, txManager)
                .reader(employeeDepartmentReader)
                .writer(myBatisWriter("io.github.riemr.shift.infrastructure.mapper.EmployeeDepartmentMapper.insert"))
                .build();
    }

    @Bean
    public FlatFileItemReader<EmployeeDepartment> employeeDepartmentReader(@Value("${csv.dir:/csv}") Path csvDir) {
        return csvReader(csvDir.resolve("employee_department.csv"),
                new String[] { "employeeCode", "departmentCode" },
                EmployeeDepartment.class);
    }

    /* === Step : employee_department_skill.csv ====================================== */
    @Bean
    public Step employeeDepartmentSkillStep(FlatFileItemReader<EmployeeDepartmentSkill> employeeDepartmentSkillReader) {
        return new StepBuilder("employeeDepartmentSkillStep", jobRepository)
                .<EmployeeDepartmentSkill, EmployeeDepartmentSkill>chunk(1000, txManager)
                .reader(employeeDepartmentSkillReader)
                .writer(myBatisWriter("io.github.riemr.shift.infrastructure.mapper.EmployeeDepartmentSkillMapper.insert"))
                .build();
    }

    @Bean
    public FlatFileItemReader<EmployeeDepartmentSkill> employeeDepartmentSkillReader(@Value("${csv.dir:/csv}") Path csvDir) {
        return csvReader(csvDir.resolve("employee_department_skill.csv"),
                new String[] { "employeeCode", "departmentCode", "skillLevel" },
                EmployeeDepartmentSkill.class);
    }

    /* === Step : work_demand_quarter.csv ========================================== */
    @Bean
    public Step workDemandQuarterStep(FlatFileItemReader<WorkDemandQuarter> workDemandQuarterReader) {
        return new StepBuilder("workDemandQuarterStep", jobRepository)
                .<WorkDemandQuarter, WorkDemandQuarter>chunk(1000, txManager)
                .reader(workDemandQuarterReader)
                .writer(myBatisWriter(
                        "io.github.riemr.shift.infrastructure.mapper.WorkDemandQuarterMapper.insert"))
                .build();
    }

    @Bean
    public FlatFileItemReader<WorkDemandQuarter> workDemandQuarterReader(
            @Value("${csv.dir:/csv}") Path csvDir) {

        // パーサ
        SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd");
        DATE_FMT.setLenient(false);
        SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm");
        TIME_FMT.setLenient(false);

        FieldSetMapper<WorkDemandQuarter> mapper = fs -> {
            try {
                var e = new WorkDemandQuarter();
                e.setStoreCode(fs.readString("storeCode"));
                e.setDepartmentCode(fs.readString("departmentCode"));
                e.setDemandDate(DATE_FMT.parse(fs.readString("demandDate")));
                e.setSlotTime(TIME_FMT.parse(fs.readString("slotTime")).toInstant().atZone(ZoneId.systemDefault()).toLocalTime());
                try { e.setTaskCode(fs.readString("taskCode")); } catch (Exception ignore) {}
                e.setRequiredUnits(fs.readInt("requiredUnits"));
                return e;
            } catch (ParseException ex) {
                throw new FlatFileParseException("work_demand_quarter parse error", "");
            }
        };

        return new FlatFileItemReaderBuilder<WorkDemandQuarter>()
                .name("workDemandQuarterReader")
                .resource(new FileSystemResource(csvDir.resolve("work_demand_quarter.csv")))
                .linesToSkip(1)
                .delimited()
                .names("storeCode","departmentCode","demandDate","slotTime","taskCode","requiredUnits")
                .fieldSetMapper(mapper)
                .build();
    }
    /* === Step : store.csv ============================================= */
    @Bean
    public Step storeStep(FlatFileItemReader<Store> storeReader) {
        return new StepBuilder("storeStep", jobRepository)
                .<Store, Store>chunk(1000, txManager)
                .reader(storeReader)
                .writer(myBatisWriter("io.github.riemr.shift.infrastructure.mapper.StoreMapper.insertSelective"))
                .build();
    }

    @Bean
    public FlatFileItemReader<Store> storeReader(
            @Value("${csv.dir:/csv}") Path csvDir) {

        return csvReader(csvDir.resolve("store.csv"), new String[] { "storeCode", "storeName", "timezone" },
                Store.class);
    }

    /* === Step : register_type.csv ========================================== */
    @Bean
    public Step registerTypeStep(FlatFileItemReader<RegisterType> registerTypeReader) {
        return new StepBuilder("registerTypeStep", jobRepository)
                .<RegisterType, RegisterType>chunk(1000, txManager)
                .reader(registerTypeReader)
                .writer(myBatisWriter("io.github.riemr.shift.infrastructure.mapper.RegisterTypeMapper.insertSelective"))
                .build();
    }

    @Bean
    public FlatFileItemReader<RegisterType> registerTypeReader(@Value("${csv.dir:/csv}") Path csvDir) {
        return csvReader(csvDir.resolve("register_type.csv"),
                new String[] { "typeCode", "typeName" },
                RegisterType.class);
    }

    /* === Step : register.csv ========================================== */
    @Bean
    public Step registerStep(FlatFileItemReader<Register> registerReader) {
        return new StepBuilder("registerStep", jobRepository)
                .<Register, Register>chunk(1000, txManager)
                .reader(registerReader)
                .writer(myBatisWriter("io.github.riemr.shift.infrastructure.mapper.RegisterMapper.insertSelective"))
                .build();
    }

    @Bean
    public FlatFileItemReader<Register> registerReader(@Value("${csv.dir:/csv}") Path csvDir) {
        return csvReader(csvDir.resolve("register.csv"),
                new String[] { "storeCode", "registerNo", "registerName", "shortName", "openPriority", "registerType",
                        "isAutoOpenTarget", "maxAllowance" },
                Register.class);
    }

    /* === Step : employee.csv ========================================== */
    @Bean
    public Step employeeStep(FlatFileItemReader<Employee> employeeReader) {
        return new StepBuilder("employeeStep", jobRepository)
                .<Employee, Employee>chunk(1000, txManager)
                .reader(employeeReader)
                .writer(myBatisWriter("io.github.riemr.shift.infrastructure.mapper.EmployeeMapper.insertSelective"))
                .build();
    }

    @Bean
    public FlatFileItemReader<Employee> employeeReader(@Value("${csv.dir:/csv}") Path csvDir) {
        
        // 時刻パーサ（HHmm形式 -> Date）
        SimpleDateFormat TIME_FMT = new SimpleDateFormat("HHmm");
        TIME_FMT.setLenient(false);
        
        // カスタムFieldSetMapper
        FieldSetMapper<Employee> mapper = fs -> {
            Employee e = new Employee();
            e.setEmployeeCode(fs.readString("employeeCode"));
            e.setStoreCode(fs.readString("storeCode"));
            e.setEmployeeName(fs.readString("employeeName"));
            e.setShortFollow(fs.readShort("shortFollow"));
            e.setMaxWorkMinutesDay(fs.readInt("maxWorkMinutesDay"));
            e.setMaxWorkDaysMonth(fs.readInt("maxWorkDaysMonth"));
            // 基本開始/終了時刻は曜日別テーブルに移行したため読み込み対象外
            return e;
        };
        
        // FlatFileItemReaderを構築
        return new FlatFileItemReaderBuilder<Employee>()
                .name("employeeReader")
                .resource(new FileSystemResource(csvDir.resolve("employee.csv")))
                .linesToSkip(1)
                .delimited()
                .names("employeeCode", "storeCode", "employeeName", "shortFollow", 
                       "maxWorkMinutesDay", "maxWorkDaysMonth", "password", "authorityCode")
                .fieldSetMapper(mapper)
                .build();
    }

    /* === Step : employee auth (password_hash, authority_code) 更新 === */
    @Bean
    public Step employeeAuthStep(FlatFileItemReader<EmployeeAuthRecord> employeeAuthReader) {
        return new StepBuilder("employeeAuthStep", jobRepository)
                .<EmployeeAuthRecord, EmployeeAuthRecord>chunk(1000, txManager)
                .reader(employeeAuthReader)
                .writer(items -> {
                    for (EmployeeAuthRecord r : items) {
                        // MyBatis直呼び出し
                        org.apache.ibatis.session.SqlSession session = sqlSessionFactory.openSession();
                        try {
                            session.update("io.github.riemr.shift.infrastructure.mapper.EmployeeMapper.updateAuthFields", r);
                            session.commit();
                        } finally {
                            session.close();
                        }
                    }
                })
                .build();
    }

    @Bean
    public FlatFileItemReader<EmployeeAuthRecord> employeeAuthReader(@Value("${csv.dir:/csv}") Path csvDir) {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

        FieldSetMapper<EmployeeAuthRecord> mapper = fs -> {
            String code = fs.readString("employeeCode");
            String rawPassword = fs.readString("password");
            String authority = fs.readString("authorityCode");
            EmployeeAuthRecord r = new EmployeeAuthRecord();
            r.setEmployeeCode(code);
            // 指示: パスワードはemployeeCodeと同じ値
            String toHash = (rawPassword != null && !rawPassword.isBlank()) ? rawPassword : code;
            r.setPasswordHash(encoder.encode(toHash));
            r.setAuthorityCode(authority != null ? authority : "USER");
            return r;
        };

        return new FlatFileItemReaderBuilder<EmployeeAuthRecord>()
                .name("employeeAuthReader")
                .resource(new FileSystemResource(csvDir.resolve("employee.csv")))
                .linesToSkip(1)
                .delimited()
                .names("employeeCode", "storeCode", "employeeName", "shortFollow",
                        "maxWorkMinutesDay", "maxWorkDaysMonth", "password", "authorityCode")
                .fieldSetMapper(mapper)
                .build();
    }

    public static class EmployeeAuthRecord {
        private String employeeCode;
        private String passwordHash;
        private String authorityCode;
        public String getEmployeeCode() { return employeeCode; }
        public void setEmployeeCode(String employeeCode) { this.employeeCode = employeeCode; }
        public String getPasswordHash() { return passwordHash; }
        public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
        public String getAuthorityCode() { return authorityCode; }
        public void setAuthorityCode(String authorityCode) { this.authorityCode = authorityCode; }
    }

    /* === Step : employee_register_skill.csv ========================================== */
    @Bean
    public Step employeeRegisterSkillStep(FlatFileItemReader<EmployeeRegisterSkill> employeeRegisterSkillReader) {
        return new StepBuilder("employeeRegisterSkillStep", jobRepository)
                .<EmployeeRegisterSkill, EmployeeRegisterSkill>chunk(1000, txManager)
                .reader(employeeRegisterSkillReader)
                .writer(myBatisWriter(
                        "io.github.riemr.shift.infrastructure.mapper.EmployeeRegisterSkillMapper.insertSelective"))
                .build();
    }

    @Bean
    public FlatFileItemReader<EmployeeRegisterSkill> employeeRegisterSkillReader(@Value("${csv.dir:/csv}") Path csvDir) {
        return csvReader(csvDir.resolve("employee_register_skill.csv"),
                new String[] { "storeCode", "employeeCode", "registerNo", "skillLevel" },
                EmployeeRegisterSkill.class);
    }

    /* === Step : employee_task_skill.csv ========================================== */
    @Bean
    public Step employeeTaskSkillStep(FlatFileItemReader<io.github.riemr.shift.infrastructure.persistence.entity.EmployeeTaskSkill> employeeTaskSkillReader) {
        return new StepBuilder("employeeTaskSkillStep", jobRepository)
                .<io.github.riemr.shift.infrastructure.persistence.entity.EmployeeTaskSkill, io.github.riemr.shift.infrastructure.persistence.entity.EmployeeTaskSkill>chunk(1000, txManager)
                .reader(employeeTaskSkillReader)
                .writer(items -> {
                    for (var r : items) {
                        // upsert
                        org.apache.ibatis.session.SqlSession session = sqlSessionFactory.openSession();
                        try {
                            session.insert("io.github.riemr.shift.infrastructure.mapper.EmployeeTaskSkillMapper.upsert", r);
                            session.commit();
                        } finally { session.close(); }
                    }
                })
                .build();
    }

    @Bean
    public FlatFileItemReader<io.github.riemr.shift.infrastructure.persistence.entity.EmployeeTaskSkill> employeeTaskSkillReader(@Value("${csv.dir:/csv}") Path csvDir) {
        return csvReader(csvDir.resolve("employee_task_skill.csv"),
                new String[] { "employeeCode", "taskCode", "skillLevel" },
                io.github.riemr.shift.infrastructure.persistence.entity.EmployeeTaskSkill.class);
    }

    /* === Step : employee_weekly_preference.csv ========================= */
    @Bean
    public Step employeeWeeklyPreferenceStep(FlatFileItemReader<EmployeeWeeklyPreference> employeeWeeklyPreferenceReader) {
        return new StepBuilder("employeeWeeklyPreferenceStep", jobRepository)
                .<EmployeeWeeklyPreference, EmployeeWeeklyPreference>chunk(1000, txManager)
                .reader(employeeWeeklyPreferenceReader)
                .writer(myBatisWriter("io.github.riemr.shift.infrastructure.mapper.EmployeeWeeklyPreferenceMapper.insert"))
                .build();
    }

    @Bean
    public FlatFileItemReader<EmployeeWeeklyPreference> employeeWeeklyPreferenceReader(@Value("${csv.dir:/csv}") Path csvDir) {
        // HH:mm を java.sql.Time にするカスタムマッパ
        FieldSetMapper<EmployeeWeeklyPreference> mapper = fs -> {
            EmployeeWeeklyPreference p = new EmployeeWeeklyPreference();
            p.setEmployeeCode(fs.readString("employeeCode"));
            p.setDayOfWeek(fs.readShort("dayOfWeek"));
            p.setWorkStyle(fs.readString("workStyle"));
            String s = fs.readString("baseStartTime");
            String e = fs.readString("baseEndTime");
            if (s != null && !s.isBlank()) {
                java.time.LocalTime lt = java.time.LocalTime.parse(s);
                p.setBaseStartTime(java.sql.Time.valueOf(lt));
            }
            if (e != null && !e.isBlank()) {
                java.time.LocalTime lt = java.time.LocalTime.parse(e);
                p.setBaseEndTime(java.sql.Time.valueOf(lt));
            }
            // storeCode は任意
            try { p.setStoreCode(fs.readString("storeCode")); } catch (Exception ignore) {}
            return p;
        };

        return new FlatFileItemReaderBuilder<EmployeeWeeklyPreference>()
                .name("employeeWeeklyPreferenceReader")
                .resource(new FileSystemResource(csvDir.resolve("employee_weekly_preference.csv")))
                .linesToSkip(1)
                .delimited()
                .names("employeeCode","dayOfWeek","workStyle","baseStartTime","baseEndTime","storeCode")
                .fieldSetMapper(mapper)
                .build();
    }

    /* === Step : register_demand_quarter.csv ========================================== */
    @Bean
    public Step registerDemandQuarterStep(FlatFileItemReader<RegisterDemandQuarter> registerDemandQuarterReader) {
        return new StepBuilder("registerDemandQuarterStep", jobRepository)
                .<RegisterDemandQuarter, RegisterDemandQuarter>chunk(1000, txManager)
                .reader(registerDemandQuarterReader)
                .writer(myBatisWriter(
                        "io.github.riemr.shift.infrastructure.mapper.RegisterDemandQuarterMapper.insertSelective"))
                .build();
    }

    @Bean
    public FlatFileItemReader<RegisterDemandQuarter> registerDemandQuarterReader(
            @Value("${csv.dir:/csv}") Path csvDir) {
    
        // --- 日付／時刻パーサ（スレッドセーフではないので 1 スレッド前提） ---
        SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd");
        DATE_FMT.setLenient(false);
        SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm");  // 秒は不要
        TIME_FMT.setLenient(false);
    
        // --- FieldSetMapper ---
        FieldSetMapper<RegisterDemandQuarter> mapper = fs -> {
            try {
                var e = new RegisterDemandQuarter();
                e.setStoreCode(fs.readString("storeCode"));
                e.setDemandDate(DATE_FMT.parse(fs.readString("demandDate")));
                e.setSlotTime (TIME_FMT.parse(fs.readString("slotTime")).toInstant().atZone(ZoneId.systemDefault()).toLocalTime());
                e.setRequiredUnits(fs.readInt("requiredUnits"));
                return e;
            } catch (ParseException ex) {
                // CSV の該当行情報も含めて Spring-Batch 流に包み直す
                throw new FlatFileParseException("日付／時刻のパースに失敗しました", "");
                // もしくは単に new IllegalStateException(ex) でも OK
            }
        };
    
        // --- reader を組み立て ---
        return new FlatFileItemReaderBuilder<RegisterDemandQuarter>()
                .name("registerDemandQuarterReader")
                .resource(new FileSystemResource(
                        csvDir.resolve("register_demand_quarter.csv")))
                .linesToSkip(1)                               // ヘッダー 1 行なら
                .delimited()
                .names("storeCode","demandDate","slotTime","requiredUnits")
                .fieldSetMapper(mapper)                       // ← ここがポイント
                .build();
    }

    /* === 共通メソッド ================================================ */
    private <T> FlatFileItemReader<T> csvReader(Path path, String[] columns, Class<T> type) {
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setDelimiter(",");
        tokenizer.setNames(columns);

        BeanWrapperFieldSetMapper<T> mapper = new BeanWrapperFieldSetMapper<>();
        mapper.setTargetType(type);

        DefaultLineMapper<T> lm = new DefaultLineMapper<>();
        lm.setLineTokenizer(tokenizer);
        lm.setFieldSetMapper(mapper);

        FlatFileItemReader<T> r = new FlatFileItemReader<>();
        r.setResource(new FileSystemResource(path));
        r.setLinesToSkip(1);
        r.setLineMapper(lm);
        return r;
    }

    private <T> MyBatisBatchItemWriter<T> myBatisWriter(String statementId) {
        MyBatisBatchItemWriter<T> w = new MyBatisBatchItemWriter<>();
        w.setSqlSessionFactory(sqlSessionFactory);
        w.setStatementId(statementId);
        w.setAssertUpdates(false); // バッチ結果チェックを無効化
        return w;
    }
}
