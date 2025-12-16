package io.github.riemr.shift.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.riemr.shift.application.repository.MonthlyTaskPlanRepository;
import io.github.riemr.shift.infrastructure.persistence.entity.MonthlyTaskPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MonthlyTaskPlanController.class)
@AutoConfigureMockMvc(addFilters = false)
class MonthlyTaskPlanControllerTest {

    @SpringBootConfiguration
    @Import(MonthlyTaskPlanController.class)
    static class TestApplication {}

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    MonthlyTaskPlanRepository repository;

    @BeforeEach
    void setup() {
        Mockito.reset(repository);
    }

    @Test
    void createDom_returns400_whenDaysMissing() throws Exception {
        var req = new java.util.LinkedHashMap<String, Object>();
        req.put("storeCode", "S001");
        req.put("taskCode", "T001");
        req.put("scheduleType", "FIXED");
        // daysOfMonth omitted

        mockMvc.perform(post("/tasks/api/monthly/dom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("daysOfMonth is required"));
    }

    @Test
    void createDom_succeeds_andCallsRepository() throws Exception {
        // Arrange mock to set generated id
        doAnswer(invocation -> {
            MonthlyTaskPlan p = invocation.getArgument(0);
            p.setPlanId(123L);
            return null;
        }).when(repository).save(any(MonthlyTaskPlan.class));

        var req = new java.util.LinkedHashMap<String, Object>();
        req.put("storeCode", "S001");
        req.put("departmentCode", "D01");
        req.put("taskCode", "T001");
        req.put("scheduleType", "FIXED");
        req.put("daysOfMonth", List.of(1, 15));

        mockMvc.perform(post("/tasks/api/monthly/dom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.planId").value(123))
                .andExpect(jsonPath("$.daysCount").value(2));

        // Verify interactions
        ArgumentCaptor<MonthlyTaskPlan> captor = ArgumentCaptor.forClass(MonthlyTaskPlan.class);
        verify(repository, times(1)).save(captor.capture());
        MonthlyTaskPlan saved = captor.getValue();
        assertThat(saved.getStoreCode()).isEqualTo("S001");
        assertThat(saved.getTaskCode()).isEqualTo("T001");
        verify(repository, times(1)).replaceDomDays(eq(123L), eq(List.of((short)1, (short)15)));
    }

    @Test
    void update_returns400_whenPlanIdMissing() throws Exception {
        var req = new java.util.LinkedHashMap<String, Object>();
        req.put("requiredStaffCount", 2);

        mockMvc.perform(put("/tasks/api/monthly/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("planId is required"));
    }

    @Test
    void update_returns404_whenNotFound() throws Exception {
        when(repository.find(999L)).thenReturn(null);
        var req = new java.util.LinkedHashMap<String, Object>();
        req.put("planId", 999);
        req.put("requiredStaffCount", 3);

        mockMvc.perform(put("/tasks/api/monthly/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_succeeds_andCallsRepository() throws Exception {
        MonthlyTaskPlan existing = new MonthlyTaskPlan();
        existing.setPlanId(1L);
        existing.setScheduleType("FIXED");
        when(repository.find(1L)).thenReturn(existing);

        var req = new java.util.LinkedHashMap<String, Object>();
        req.put("planId", 1);
        req.put("requiredStaffCount", 5);

        mockMvc.perform(put("/tasks/api/monthly/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        verify(repository, times(1)).update(any(MonthlyTaskPlan.class));
    }

    @Test
    void delete_succeeds_andCallsRepository() throws Exception {
        mockMvc.perform(delete("/tasks/api/monthly/delete/{id}", 10L))
                .andExpect(status().isOk());
        verify(repository, times(1)).delete(10L);
    }
}
