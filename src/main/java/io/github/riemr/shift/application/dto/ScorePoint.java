package io.github.riemr.shift.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ScorePoint {
    private long timeMillis;
    private int initScore;
    private int hardScore;
    private int softScore;
}

