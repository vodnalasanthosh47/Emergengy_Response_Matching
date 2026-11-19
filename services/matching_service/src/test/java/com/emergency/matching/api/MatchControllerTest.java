package com.emergency.matching.api;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.emergency.common.model.Category;
import com.emergency.common.request.RequestDocument;
import com.emergency.common.request.RequestStatus;
import com.emergency.common.request.RequestView;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceView;
import com.emergency.matching.domain.ConcurrentUpdateException;
import com.emergency.matching.domain.DependencyUnavailableException;
import com.emergency.matching.domain.InvalidRequestException;
import com.emergency.matching.domain.MatchOutcome;
import com.emergency.matching.domain.MatchingService;
import com.emergency.matching.support.Fixtures;

/** The HTTP mapping of each outcome (contracts/openapi/matching-service-api.yaml), with the domain mocked. */
@WebMvcTest(MatchController.class)
@Import({MatchControllerTest.FixedClock.class, com.emergency.matching.config.JacksonConfig.class})
class MatchControllerTest {

    static final String ID = "f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d";

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-19T10:15:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    MatchingService matching;

    private final ResourceDocument resource = Fixtures.resource(Category.WATER, 0.001, "500", "480");

    private RequestView request(RequestStatus status) {
        RequestDocument d = Fixtures.request(ID, Category.WATER, Fixtures.LAT, Fixtures.LNG, "{\"quantity\":20,\"unit\":\"L\"}", status);
        return RequestView.of(status == RequestStatus.MATCHED ? d.matchedTo(resource.resourceId(), Fixtures.T0) : d, 2);
    }

    @Test
    void matchedIs200WithTheAssignment() throws Exception {
        when(matching.match(ID)).thenReturn(new MatchOutcome.Matched(request(RequestStatus.MATCHED),
                ResourceView.of(resource, 2), 0.11123456));
        mvc.perform(post("/match/" + ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(ID))
                .andExpect(jsonPath("$.resourceId").value(resource.resourceId()))
                .andExpect(jsonPath("$.distanceKm").value(0.111))
                .andExpect(jsonPath("$.request.status").value("MATCHED"))
                .andExpect(jsonPath("$.request.assignedResourceId").value(resource.resourceId()))
                .andExpect(jsonPath("$.resource.capacity.remaining").value(480))
                .andExpect(jsonPath("$.resource.version").value(2));
    }

    @Test
    void noMatchIs422() throws Exception {
        when(matching.match(ID)).thenReturn(new MatchOutcome.NoMatch(request(RequestStatus.UNMATCHED)));
        mvc.perform(post("/match/" + ID))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("NO_MATCH"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("UNMATCHED")))
                .andExpect(jsonPath("$.timestamp").value("2026-11-19T10:15:00Z"));
    }

    @Test
    void notMatchableIs409() throws Exception {
        when(matching.match(ID)).thenReturn(new MatchOutcome.NotMatchable(request(RequestStatus.MATCHED)));
        mvc.perform(post("/match/" + ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("request is MATCHED; only PENDING or UNMATCHED requests can be matched"));
    }

    @Test
    void unknownIs404() throws Exception {
        when(matching.match(ID)).thenReturn(new MatchOutcome.NotFound(ID));
        mvc.perform(post("/match/" + ID)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void exceptionsMapToStatusCodes() throws Exception {
        doThrow(new InvalidRequestException("requestId must be a UUID")).when(matching).match("bad");
        mvc.perform(post("/match/bad")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("requestId must be a UUID"));

        doThrow(new DependencyUnavailableException("storage down: internal detail")).when(matching).match(ID);
        mvc.perform(post("/match/" + ID)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("a dependency is unavailable, retry later"));

        String other = "00000000-0000-4000-8000-000000000000";
        doThrow(new ConcurrentUpdateException("request " + other + " is being changed concurrently")).when(matching).match(other);
        mvc.perform(post("/match/" + other)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.endsWith("; retry")));

        String bug = "11111111-1111-4111-8111-111111111111";
        doThrow(new IllegalStateException("corrupt")).when(matching).match(bug);
        mvc.perform(post("/match/" + bug)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR")).andExpect(jsonPath("$.message").value("internal error"));
    }

    @Test
    void onlyPostOnMatch() throws Exception {
        mvc.perform(get("/match/" + ID)).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mvc.perform(get("/nope")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}
