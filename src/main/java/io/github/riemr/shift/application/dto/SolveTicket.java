package io.github.riemr.shift.application.dto;

public record SolveTicket(String ticketId, long startMillis, long expectedFinishMillis) {}
