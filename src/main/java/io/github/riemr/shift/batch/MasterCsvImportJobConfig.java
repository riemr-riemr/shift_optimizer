package io.github.riemr.shift.batch;

import io.github.riemr.shift.domain.*;
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
@Profile("batch")
@RequiredArgsConstructor
public class MasterCsvImportJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager txManager;
    private final SqlSessionFactory sqlSessionFactory;

    /* === Job =========================================================== */
    @Bean
    public Job masterImportJob(Step storeStep,
            Step registerTypeStep,
            Step registerStep,
            Step employeeStep,
            Step employeeRegisterSkillStep,
            Step registerDemandQuarterStep) {
        return new JobBuilder("masterImportJob", jobRepository)
                .incrementer(new RunIdIncrementer()) // run.id を自動付与
                .start(storeStep)
                .next(registerTypeStep)
                .next(registerStep)
                .next(employeeStep)
                .next(employeeRegisterSkillStep)
                .next(registerDemandQuarterStep)
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
            @Value("${csv.dir}") Path csvDir) {

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
    public FlatFileItemReader<RegisterType> registerTypeReader(@Value("${csv.dir}") Path csvDir) {
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
    public FlatFileItemReader<Register> registerReader(@Value("${csv.dir}") Path csvDir) {
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
    public FlatFileItemReader<Employee> employeeReader(@Value("${csv.dir}") Path csvDir) {
        
        // 時刻パーサ（HHmm形式 -> Date）
        SimpleDateFormat TIME_FMT = new SimpleDateFormat("HHmm");
        TIME_FMT.setLenient(false);
        
        // カスタムFieldSetMapper
        FieldSetMapper<Employee> mapper = fs -> {
            try {
                Employee e = new Employee();
                e.setEmployeeCode(fs.readString("employeeCode"));
                e.setStoreCode(fs.readString("storeCode"));
                e.setEmployeeName(fs.readString("employeeName"));
                e.setShortFollow(fs.readShort("shortFollow"));
                e.setMaxWorkMinutesDay(fs.readInt("maxWorkMinutesDay"));
                e.setMaxWorkDaysMonth(fs.readInt("maxWorkDaysMonth"));
                
                // 時刻フィールドの処理（空の場合はnull）
                String startTimeStr = fs.readString("baseStartTime");
                if (startTimeStr != null && !startTimeStr.trim().isEmpty()) {
                    e.setBaseStartTime(TIME_FMT.parse(startTimeStr));
                }
                
                String endTimeStr = fs.readString("baseEndTime");
                if (endTimeStr != null && !endTimeStr.trim().isEmpty()) {
                    e.setBaseEndTime(TIME_FMT.parse(endTimeStr));
                }
                
                return e;
            } catch (ParseException ex) {
                throw new FlatFileParseException("時刻のパースに失敗しました", "");
            }
        };
        
        // FlatFileItemReaderを構築
        return new FlatFileItemReaderBuilder<Employee>()
                .name("employeeReader")
                .resource(new FileSystemResource(csvDir.resolve("employee.csv")))
                .linesToSkip(1)
                .delimited()
                .names("employeeCode", "storeCode", "employeeName", "shortFollow", 
                       "maxWorkMinutesDay", "maxWorkDaysMonth", "baseStartTime", "baseEndTime")
                .fieldSetMapper(mapper)
                .build();
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
    public FlatFileItemReader<EmployeeRegisterSkill> employeeRegisterSkillReader(@Value("${csv.dir}") Path csvDir) {
        return csvReader(csvDir.resolve("employee_register_skill.csv"),
                new String[] { "storeCode", "employeeCode", "registerNo", "skillLevel" },
                EmployeeRegisterSkill.class);
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
            @Value("${csv.dir}") Path csvDir) {
    
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
