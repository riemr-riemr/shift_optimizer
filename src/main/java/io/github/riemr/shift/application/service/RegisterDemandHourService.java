package io.github.riemr.shift.application.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.riemr.shift.application.dto.RegisterDemandHourDto;
import io.github.riemr.shift.domain.RegisterDemandQuarter;
import io.github.riemr.shift.infrastructure.mapper.RegisterDemandQuarterMapper;
import lombok.RequiredArgsConstructor;

/**
 * Facade service used by the controller / UI to read & persist register‐demand figures.
 * <p>
 * ‑ UI works at <strong>hour</strong> resolution.<br>
 * ‑ DB keeps <strong>15‑minute</strong> rows. Conversion rules are:
 * <ul>
 *   <li>When loading – the hourly demand is MAX of the 4 quarter rows inside that hour.</li>
 *   <li>When saving  – the same hourly demand is copied to all 4 quarter rows.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RegisterDemandHourService {
    private final RegisterDemandQuarterMapper mapper;

    /**
     * Fetch hourly‑level demand for the UI (max of four quarter slots in the hour).
     */
    public List<RegisterDemandHourDto> findHourlyDemands(String storeCode,
        LocalDate targetDate) {
        List<RegisterDemandQuarter> quarters =
            mapper.selectByStoreAndDate(storeCode, targetDate);

        // ★ null を除外し、slot_time → 時間 の取得をシンプルに
        Map<Integer, Integer> hour2units =
        quarters.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        q -> q.getSlotTime().getHour(),
                        Collectors.collectingAndThen(
                                Collectors.mapping(RegisterDemandQuarter::getRequiredUnits,
                                                   Collectors.maxBy(Integer::compareTo)),
                                opt -> opt.orElse(0))));

        List<RegisterDemandHourDto> result = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            result.add(new RegisterDemandHourDto(
                storeCode, targetDate, LocalTime.of(h, 0),
            hour2units.getOrDefault(h, 0)));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Overwrite a day’s quarter rows with the supplied hourly list.
     */
    @Transactional
    public void saveHourlyDemands(String storeCode, LocalDate targetDate, List<RegisterDemandHourDto> hourlyList) {
        if (hourlyList.size() != 24) {
            throw new IllegalArgumentException("Exactly 24 hourly rows (00‑23) are required");
        }
        // Expand each hour into 4 quarter rows (same value)
        List<RegisterDemandQuarter> quarters = new ArrayList<>(96);
        for (RegisterDemandHourDto dto : hourlyList) {
            LocalTime base = dto.getHour();
            int units = dto.getRequiredUnits();
            for (int i = 0; i < 4; i++) {
                RegisterDemandQuarter q = new RegisterDemandQuarter();
                q.setStoreCode(storeCode);
                q.setDemandDate(java.sql.Date.valueOf(targetDate));
                q.setSlotTime(base.plusMinutes(i * 15));
                q.setRequiredUnits(units);
                quarters.add(q);
            }
        }
        mapper.deleteByStoreAndDate(storeCode, targetDate);
        mapper.batchInsert(quarters);
    }
}