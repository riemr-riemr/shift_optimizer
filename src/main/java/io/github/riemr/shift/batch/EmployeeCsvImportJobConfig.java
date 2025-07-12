package io.github.riemr.shift.batch;

import io.github.riemr.shift.domain.Employee;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.batch.MyBatisBatchItemWriter;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.PathResource;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableBatchProcessing
public class EmployeeCsvImportJobConfig {

    /* ---------- Job ---------- */
    @Bean
    public Job employeeCsvImportJob(JobRepository jobRepository, Step employeeCsvStep) {
        return new JobBuilder("employeeCsvImportJob", jobRepository)
                .start(employeeCsvStep)
                .build();
    }

    /* ---------- Step ---------- */
    @Bean
    public Step employeeCsvStep(JobRepository jobRepository,
                                PlatformTransactionManager transactionManager,
                                FlatFileItemReader<Employee> employeeReader,
                                MyBatisBatchItemWriter<Employee> employeeWriter) {

        return new StepBuilder("employeeCsvStep", jobRepository)
                .<Employee, Employee>chunk(1_000, transactionManager)
                .reader(employeeReader)
                .writer(employeeWriter)
                .build();
    }

    /* ---------- Reader ---------- */
    @Bean
    public FlatFileItemReader<Employee> employeeReader(@Value("${csv.path}") String csvPath) {

        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setNames("code", "name", "maxHoursPerDay", "maxHoursPerWeek");

        BeanWrapperFieldSetMapper<Employee> mapper = new BeanWrapperFieldSetMapper<>();
        mapper.setTargetType(Employee.class);

        DefaultLineMapper<Employee> lineMapper = new DefaultLineMapper<>();
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(mapper);

        FlatFileItemReader<Employee> reader = new FlatFileItemReader<>();
        reader.setResource(new PathResource(csvPath));
        reader.setLinesToSkip(1);               // ヘッダ行
        reader.setLineMapper(lineMapper);
        return reader;
    }

    /* ---------- Writer (MyBatis) ---------- */
    @Bean
    public MyBatisBatchItemWriter<Employee> employeeWriter(SqlSessionFactory sqlSessionFactory) {
        MyBatisBatchItemWriter<Employee> writer = new MyBatisBatchItemWriter<>();
        writer.setSqlSessionFactory(sqlSessionFactory);
        writer.setStatementId(
            "io.github.riemr.shift.infrastructure.mapper.EmployeeMapper.insertEmployee"
        );
        return writer;
    }
}
