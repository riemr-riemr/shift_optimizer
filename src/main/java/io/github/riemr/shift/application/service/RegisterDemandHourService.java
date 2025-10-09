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
import io.github.riemr.shift.application.dto.DemandIntervalDto;
import io.github.riemr.shift.application.util.TimeIntervalQuarterUtils;
import io.github.riemr.shift.infrastructure.mapper.RegisterDemandIntervalMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterDemandQuarter;
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
    private final RegisterDemandIntervalMapper intervalMapper;

    /**
     * Fetch hourly‑level demand for the UI (max of four quarter slots in the hour).
     */
    public List<RegisterDemandHourDto> findHourlyDemands(String storeCode,
        LocalDate targetDate) {
        var intervals = intervalMapper.selectByStoreAndDate(storeCode, targetDate);
        var quarters = TimeIntervalQuarterUtils.splitAll(intervals);

        Map<Integer, Integer> hour2units = quarters.stream()
                .collect(Collectors.groupingBy(
                        q -> q.getStart().getHour(),
                        Collectors.collectingAndThen(
                                Collectors.mapping(q -> Objects.requireNonNullElse(q.getDemand(), 0),
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
        // Replace the day's intervals with hourly [from,to) rows
        intervalMapper.deleteByStoreAndDate(storeCode, targetDate);
        for (RegisterDemandHourDto dto : hourlyList) {
            LocalTime from = dto.getHour();
            LocalTime to = from.plusHours(1);
            DemandIntervalDto interval = DemandIntervalDto.builder()
                    .storeCode(storeCode)
                    .targetDate(targetDate)
                    .from(from)
                    .to(to)
                    .demand(dto.getRequiredUnits())
                    .build();
            intervalMapper.upsert(interval);
        }
    }

    /**
     * Build quarter-level demand array (size 96) from intervals.
     */
    public int[] getQuarterDemands(String storeCode, LocalDate targetDate) {
        var intervals = intervalMapper.selectByStoreAndDate(storeCode, targetDate);
        var quarters = TimeIntervalQuarterUtils.splitAll(intervals);
        int[] arr = new int[96];
        for (var qs : quarters) {
            int idx = TimeIntervalQuarterUtils.toQuarterIndex(qs.getStart());
            arr[idx] = qs.getDemand() == null ? 0 : qs.getDemand();
        }
        return arr;
    }

    /**
     * Save quarter demands (96 size) by merging contiguous same values into intervals.
     */
    @Transactional
    public void saveQuarterDemands(String storeCode, LocalDate targetDate, List<Integer> quarterDemands) {
        if (quarterDemands == null || quarterDemands.size() != 96) {
            throw new IllegalArgumentException("quarterDemands must have 96 entries");
        }
        intervalMapper.deleteByStoreAndDate(storeCode, targetDate);
        int prev = -1;
        int startIdx = 0;
        for (int i = 0; i <= 96; i++) {
            int cur = (i < 96) ? Math.max(0, quarterDemands.get(i)) : -1; // sentinel
            if (i == 0) { prev = cur; startIdx = 0; continue; }
            if (cur != prev) {
                if (prev > 0) {
                    LocalTime from = TimeIntervalQuarterUtils.fromQuarterIndex(startIdx);
                    LocalTime to = TimeIntervalQuarterUtils.fromQuarterIndex(i);
                    DemandIntervalDto dto = DemandIntervalDto.builder()
                            .storeCode(storeCode)
                            .targetDate(targetDate)
                            .from(from)
                            .to(to)
                            .demand(prev)
                            .build();
                    intervalMapper.upsert(dto);
                }
                startIdx = i;
                prev = cur;
            }
        }
    }
}
