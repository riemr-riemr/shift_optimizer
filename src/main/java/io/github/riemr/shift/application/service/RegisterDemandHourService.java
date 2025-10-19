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
        return getQuarterDemands(storeCode, targetDate, 15);
    }

    public int[] getQuarterDemands(String storeCode, LocalDate targetDate, int minutesPerSlot) {
        var intervals = intervalMapper.selectByStoreAndDate(storeCode, targetDate);
        var quarters = TimeIntervalQuarterUtils.splitAll(intervals, minutesPerSlot);
        int slotCount = 1440 / minutesPerSlot;
        int[] arr = new int[slotCount];
        for (var qs : quarters) {
            int idx = TimeIntervalQuarterUtils.toSlotIndex(qs.getStart(), minutesPerSlot);
            arr[idx] = qs.getDemand() == null ? 0 : qs.getDemand();
        }
        return arr;
    }

    /**
     * Save quarter demands (96 size) by merging contiguous same values into intervals.
     */
    @Transactional
    public void saveQuarterDemands(String storeCode, LocalDate targetDate, List<Integer> quarterDemands) {
        if (quarterDemands == null || quarterDemands.isEmpty()) {
            throw new IllegalArgumentException("quarterDemands must not be empty");
        }
        int slots = quarterDemands.size();
        if (1440 % slots != 0) throw new IllegalArgumentException("invalid slot count: " + slots);
        int minutesPerSlot = 1440 / slots;
        intervalMapper.deleteByStoreAndDate(storeCode, targetDate);
        int prev = -1;
        int startIdx = 0;
        for (int i = 0; i <= slots; i++) {
            int cur = (i < slots) ? Math.max(0, quarterDemands.get(i)) : -1; // sentinel
            if (i == 0) { prev = cur; startIdx = 0; continue; }
            if (cur != prev) {
                if (prev > 0) {
                    LocalTime from = TimeIntervalQuarterUtils.fromSlotIndex(startIdx, minutesPerSlot);
                    LocalTime to = TimeIntervalQuarterUtils.fromSlotIndex(i, minutesPerSlot);
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
