package io.github.riemr.shift.application.util;

import io.github.riemr.shift.application.dto.DemandIntervalDto;
import io.github.riemr.shift.application.dto.QuarterSlot;
import io.github.riemr.shift.application.dto.TimeInterval;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class TimeIntervalQuarterUtils {
    private TimeIntervalQuarterUtils() {}

    public static boolean isAligned(LocalTime t) {
        return t.getMinute() % 15 == 0 && t.getSecond() == 0 && t.getNano() == 0;
    }

    public static LocalTime normalizeToQuarter(LocalTime t, RoundingMode mode) {
        int totalMinutes = t.getHour() * 60 + t.getMinute();
        int rem = totalMinutes % 15;
        if (rem == 0 && t.getSecond() == 0 && t.getNano() == 0) return t.withSecond(0).withNano(0);

        int base = totalMinutes - rem;
        int rounded;
        switch (mode) {
            case DOWN:
                rounded = base;
                break;
            case UP: {
                rounded = base + 15;
                break;
            }
            case HALF_UP:
            case HALF_EVEN:
            case HALF_DOWN: {
                rounded = (rem >= 8 ? base + 15 : base);
                break;
            }
            default:
                throw new IllegalArgumentException("Unsupported rounding mode: " + mode);
        }
        if (rounded >= 24 * 60) {
            // cap at 24:00 -> 00:00 next day not representable in LocalTime, so return 23:59:59.999999999 or 00:00.
            // For our usage, cap to 23:45 to keep [from, to) within day.
            rounded = 23 * 60 + 45;
        }
        return LocalTime.of(rounded / 60, rounded % 60);
    }

    public static int toQuarterIndex(LocalTime t) {
        if (!isAligned(t)) throw new IllegalArgumentException("Time not 15-min aligned: " + t);
        return t.getHour() * 4 + t.getMinute() / 15;
    }

    public static LocalTime fromQuarterIndex(int idx) {
        if (idx < 0 || idx > 95) throw new IllegalArgumentException("Quarter index out of range: " + idx);
        return LocalTime.of(idx / 4, (idx % 4) * 15);
    }

    public static List<QuarterSlot> split(DemandIntervalDto interval) {
        Objects.requireNonNull(interval, "interval");
        LocalTime from = interval.getFrom();
        LocalTime to = interval.getTo();
        if (from == null || to == null) throw new IllegalArgumentException("from/to required");
        if (!isAligned(from) || !isAligned(to)) throw new IllegalArgumentException("from/to must be 15-min aligned");
        if (!to.isAfter(from)) throw new IllegalArgumentException("to must be after from");

        List<QuarterSlot> res = new ArrayList<>();
        LocalTime cur = from;
        while (cur.isBefore(to)) {
            res.add(QuarterSlot.builder()
                    .storeCode(interval.getStoreCode())
                    .departmentCode(interval.getDepartmentCode())
                    .date(interval.getTargetDate())
                    .start(cur)
                    .demand(interval.getDemand())
                    .taskCode(interval.getTaskCode())
                    .build());
            cur = cur.plusMinutes(15);
        }
        return res;
    }

    public static List<QuarterSlot> splitAll(Collection<DemandIntervalDto> intervals) {
        if (intervals == null || intervals.isEmpty()) return List.of();

        Map<Key, Integer> agg = new HashMap<>();
        for (DemandIntervalDto dto : intervals) {
            for (QuarterSlot qs : split(dto)) {
                Key k = new Key(qs.getStoreCode(), qs.getDepartmentCode(), qs.getDate(), qs.getStart(), qs.getTaskCode());
                agg.merge(k, Optional.ofNullable(qs.getDemand()).orElse(0), Integer::sum);
            }
        }
        return agg.entrySet().stream()
                .map(e -> QuarterSlot.builder()
                        .storeCode(e.getKey().storeCode)
                        .departmentCode(e.getKey().departmentCode)
                        .date(e.getKey().date)
                        .start(e.getKey().start)
                        .taskCode(e.getKey().taskCode)
                        .demand(e.getValue())
                        .build())
                .sorted(Comparator.comparing(QuarterSlot::getStoreCode, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(q -> Optional.ofNullable(q.getDepartmentCode()).orElse(""))
                        .thenComparing(QuarterSlot::getDate)
                        .thenComparing(QuarterSlot::getStart)
                        .thenComparing(q -> Optional.ofNullable(q.getTaskCode()).orElse("")))
                .toList();
    }

    public static List<TimeInterval> merge(Collection<QuarterSlot> quarters) {
        if (quarters == null || quarters.isEmpty()) return List.of();

        // Group by non-time keys and demand value
        Map<MergeKey, List<QuarterSlot>> grouped = quarters.stream()
                .collect(Collectors.groupingBy(q -> new MergeKey(
                        q.getStoreCode(), q.getDepartmentCode(), q.getDate(), q.getTaskCode(),
                        Optional.ofNullable(q.getDemand()).orElse(0)
                )));

        List<TimeInterval> res = new ArrayList<>();
        for (Map.Entry<MergeKey, List<QuarterSlot>> entry : grouped.entrySet()) {
            List<QuarterSlot> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparing(QuarterSlot::getStart))
                    .toList();
            if (sorted.isEmpty()) continue;

            LocalTime runStart = sorted.get(0).getStart();
            LocalTime prev = runStart;
            for (int i = 1; i < sorted.size(); i++) {
                LocalTime cur = sorted.get(i).getStart();
                if (!cur.equals(prev.plusMinutes(15))) {
                    res.add(TimeInterval.builder().date(entry.getKey().date).from(runStart).to(prev.plusMinutes(15)).build());
                    runStart = cur;
                }
                prev = cur;
            }
            res.add(TimeInterval.builder().date(entry.getKey().date).from(runStart).to(prev.plusMinutes(15)).build());
        }
        // Stable order
        return res.stream()
                .sorted(Comparator.comparing(TimeInterval::getDate)
                        .thenComparing(TimeInterval::getFrom)
                        .thenComparing(TimeInterval::getTo))
                .toList();
    }

    private record Key(String storeCode, String departmentCode, LocalDate date, LocalTime start, String taskCode) {}

    private record MergeKey(String storeCode, String departmentCode, LocalDate date, String taskCode, int demand) {}
}

