package io.github.riemr.shift.application.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
import io.github.riemr.shift.infrastructure.mapper.RegisterMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.Register;
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
    private final RegisterMapper registerMapper;

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
                        Collectors.summingInt(q -> Objects.requireNonNullElse(q.getDemand(), 0))));

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
        // Replace the day's intervals with hourly [from,to) rows (assign registers by priority order)
        intervalMapper.deleteByStoreAndDate(storeCode, targetDate);
        var registers = registerMapper.selectByStoreCode(storeCode).stream()
                .sorted(Comparator.comparing(Register::getOpenPriority, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Register::getRegisterNo))
                .toList();
        for (RegisterDemandHourDto dto : hourlyList) {
            int required = Math.max(0, dto.getRequiredUnits());
            for (int i = 0; i < Math.min(required, registers.size()); i++) {
                LocalTime from = dto.getHour();
                LocalTime to = from.plusHours(1);
                DemandIntervalDto interval = DemandIntervalDto.builder()
                        .storeCode(storeCode)
                        .targetDate(targetDate)
                        .from(from)
                        .to(to)
                        .demand(1)
                        .registerNo(registers.get(i).getRegisterNo())
                        .build();
                intervalMapper.upsert(interval);
            }
        }
    }

    /**
     * スロット需要配列（サイズ=1440/minutesPerSlot）をintervalから構築します。
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
            arr[idx] += qs.getDemand() == null ? 0 : qs.getDemand();
        }
        return arr;
    }

    public List<String> getRegisterDemandCells(String storeCode, LocalDate targetDate, int minutesPerSlot) {
        var intervals = intervalMapper.selectByStoreAndDate(storeCode, targetDate);
        var quarters = TimeIntervalQuarterUtils.splitAll(intervals, minutesPerSlot);
        List<String> cells = new ArrayList<>();
        for (var qs : quarters) {
            if (qs.getRegisterNo() == null) continue;
            int idx = TimeIntervalQuarterUtils.toSlotIndex(qs.getStart(), minutesPerSlot);
            cells.add(qs.getRegisterNo() + ":" + idx);
        }
        return cells;
    }

    /**
     * スロット需要配列（サイズ=1440/minutesPerSlot相当）を、連続区間にマージしてintervalとして保存します。
     */
    @Transactional
    public void saveQuarterDemands(String storeCode, LocalDate targetDate, List<String> slotCells, int minutesPerSlot) {
        if (slotCells == null) {
            throw new IllegalArgumentException("slotCells must not be null");
        }
        intervalMapper.deleteByStoreAndDate(storeCode, targetDate);
        if (slotCells.isEmpty()) {
            return;
        }
        Map<Integer, boolean[]> byRegister = new HashMap<>();
        for (String cell : slotCells) {
            if (cell == null || cell.isBlank()) continue;
            String[] parts = cell.split(":");
            if (parts.length != 2) continue;
            int registerNo = Integer.parseInt(parts[0]);
            int slotIdx = Integer.parseInt(parts[1]);
            int slots = 1440 / minutesPerSlot;
            if (slotIdx < 0 || slotIdx >= slots) continue;
            boolean[] arr = byRegister.computeIfAbsent(registerNo, k -> new boolean[slots]);
            arr[slotIdx] = true;
        }
        for (var entry : byRegister.entrySet()) {
            int registerNo = entry.getKey();
            boolean[] arr = entry.getValue();
            int prev = -1;
            int startIdx = 0;
            for (int i = 0; i <= arr.length; i++) {
                int cur = (i < arr.length && arr[i]) ? 1 : 0;
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
                                .demand(1)
                                .registerNo(registerNo)
                                .build();
                        intervalMapper.upsert(dto);
                    }
                    startIdx = i;
                    prev = cur;
                }
            }
        }
    }
}
