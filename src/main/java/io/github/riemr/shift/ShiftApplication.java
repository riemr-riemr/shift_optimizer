package io.github.riemr.shift;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("io.github.riemr.shift.infrastructure.mapper")
public class ShiftApplication {

	public static void main(String[] args) {
		SpringApplication.run(ShiftApplication.class, args);
	}

}
