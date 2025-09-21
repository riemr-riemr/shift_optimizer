package io.github.riemr.shift.application.service;

import io.github.riemr.shift.infrastructure.mapper.AppSettingMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.AppSetting;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppSettingService {
    private static final String KEY_SHIFT_START_DAY = "shift_cycle_start_day";
    private final AppSettingMapper mapper;

    public int getShiftCycleStartDay() {
        AppSetting s = mapper.selectByKey(KEY_SHIFT_START_DAY);
        if (s == null) return 1;
        try {
            int v = Integer.parseInt(s.getSettingValue());
            if (v < 1 || v > 28) return 1;
            return v;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @Transactional
    public void updateShiftCycleStartDay(int day) {
        if (day < 1 || day > 28) throw new IllegalArgumentException("start day must be 1..28");
        mapper.upsert(KEY_SHIFT_START_DAY, Integer.toString(day));
    }
}
