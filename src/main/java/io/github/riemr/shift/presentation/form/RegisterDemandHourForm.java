package io.github.riemr.shift.presentation.form;

import java.time.LocalDate;
import java.util.List;

import io.github.riemr.shift.application.dto.RegisterDemandHourDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterDemandHourForm {

    private String storeCode;
    private LocalDate targetDate;
    private List<RegisterDemandHourDto> hours;
}