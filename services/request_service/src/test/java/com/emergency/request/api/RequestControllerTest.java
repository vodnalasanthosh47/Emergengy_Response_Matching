package com.emergency.request.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.emergency.common.model.Category;
import com.emergency.common.model.Location;
import com.emergency.common.request.RequestStatus;
import com.emergency.common.request.RequestView;
import com.emergency.request.domain.DependencyUnavailableException;
import com.emergency.request.domain.InvalidRequestException;
import com.emergency.request.domain.NewRequest;
import com.emergency.request.domain.RequestConflictException;
import com.emergency.request.domain.RequestNotFoundException;
import com.emergency.request.domain.RequestService;

import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP layer with the application's real Jackson and validation settings, and the
 * domain mocked: status codes, the error JSON, and that bad input never reaches the domain.
 */
@WebMvcTest(RequestController.class)
@Import({RequestControllerTest.FixedClock.class, com.emergency.request.config.JacksonConfig.class})
class RequestControllerTest {

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
    RequestService service;

    private final JsonMapper json = JsonMapper.builder().build();

    private RequestView view(RequestStatus status) {
        return new RequestView(ID, Category.WATER, new Location(12.9716, 77.5946), "tdr1v9", status, null,
                json.readTree("{\"quantity\":20,\"unit\":\"L\"}"), null,
                Instant.parse("2026-11-19T10:15:00.112Z"), Instant.parse("2026-11-19T10:15:00.112Z"), 1);
    }

    @Test
    void createReturns201WithLocationAndTheRequest() throws Exception {
        when(service.create(any())).thenReturn(view(RequestStatus.PENDING));
        mvc.perform(post("/requests").contentType(MediaType.APPLICATION_JSON).content("""
                        {"type":"WATER","location":{"lat":12.9716,"lng":77.5946},"details":{"quantity":20},
                         "requester":{"name":"Ravi","contact":"+91"}}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/requests/" + ID))
                .andExpect(jsonPath("$.requestId").value(ID))
                .andExpect(jsonPath("$.geoCell").value("tdr1v9"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.assignedResourceId").isEmpty())
                .andExpect(jsonPath("$.createdAt").value("2026-11-19T10:15:00.112Z"));
        verify(service).create(eq(new NewRequest(Category.WATER, new Location(12.9716, 77.5946),
                json.readTree("{\"quantity\":20}"), new com.emergency.common.request.Requester("Ravi", "+91"))));
    }

    /** Bad bodies are rejected by the HTTP layer with the standard error JSON; the domain is never called. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "missing type        | {\"location\":{\"lat\":1,\"lng\":2},\"details\":{}}                         | type",
            "lowercase type      | {\"type\":\"water\",\"location\":{\"lat\":1,\"lng\":2},\"details\":{}}      | invalid value for 'type'",
            "unknown type        | {\"type\":\"FIRE\",\"location\":{\"lat\":1,\"lng\":2},\"details\":{}}       | invalid value for 'type'",
            "missing location    | {\"type\":\"WATER\",\"details\":{}}                                         | location",
            "lat out of range    | {\"type\":\"WATER\",\"location\":{\"lat\":91,\"lng\":2},\"details\":{}}     | location.lat",
            "lng out of range    | {\"type\":\"WATER\",\"location\":{\"lat\":1,\"lng\":-181},\"details\":{}}   | location.lng",
            "missing lat         | {\"type\":\"WATER\",\"location\":{\"lng\":2},\"details\":{}}                | location.lat",
            "lat not a number    | {\"type\":\"WATER\",\"location\":{\"lat\":\"x\",\"lng\":2},\"details\":{}}  | invalid value for 'location.lat'",
            "lat number as text  | {\"type\":\"WATER\",\"location\":{\"lat\":\"12.97\",\"lng\":2},\"details\":{}} | invalid value for 'location.lat'",
            "lng number as text  | {\"type\":\"WATER\",\"location\":{\"lat\":1,\"lng\":\"77.59\"},\"details\":{}} | invalid value for 'location.lng'",
            "missing details     | {\"type\":\"WATER\",\"location\":{\"lat\":1,\"lng\":2}}                     | details",
            "client sent geoCell | {\"type\":\"WATER\",\"location\":{\"lat\":1,\"lng\":2},\"details\":{},\"geoCell\":\"tdr1v9\"} | unknown field 'geoCell'",
            "client sent status  | {\"type\":\"WATER\",\"location\":{\"lat\":1,\"lng\":2},\"details\":{},\"status\":\"MATCHED\"} | unknown field 'status'",
            "unknown location fld| {\"type\":\"WATER\",\"location\":{\"lat\":1,\"lng\":2,\"alt\":3},\"details\":{}} | unknown field 'alt'",
            "requester too long  | {\"type\":\"WATER\",\"location\":{\"lat\":1,\"lng\":2},\"details\":{},\"requester\":{\"name\":\"%s\"}} | requester.name",
            "not json            | not json                                                                     | JSON object",
            "json array          | []                                                                           | JSON object",
    })
    void badBodiesAre400(String name, String body, String message) throws Exception {
        mvc.perform(post("/requests").contentType(MediaType.APPLICATION_JSON).content(body.formatted("x".repeat(201))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(message)))
                .andExpect(jsonPath("$.timestamp").value("2026-11-19T10:15:00Z"));
        verify(service, never()).create(any());
    }

    @Test
    void domainErrorsMapToStatusCodes() throws Exception {
        String body = "{\"type\":\"WATER\",\"location\":{\"lat\":1,\"lng\":2},\"details\":{\"quantity\":0}}";
        doThrow(new InvalidRequestException("details.quantity must be a number > 0")).when(service).create(any());
        mvc.perform(post("/requests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("details.quantity must be a number > 0"));

        doThrow(new DependencyUnavailableException("broker down: secret internals")).when(service).create(any());
        mvc.perform(post("/requests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("a dependency is unavailable, retry later")); // no internals leaked

        when(service.get(ID)).thenThrow(new RequestNotFoundException("no request with id " + ID));
        mvc.perform(get("/requests/" + ID)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));

        when(service.updateStatus(ID, RequestStatus.FULFILLED)).thenThrow(new RequestConflictException("cannot change status from PENDING to FULFILLED"));
        mvc.perform(put("/requests/" + ID + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FULFILLED\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("CONFLICT"));

        when(service.get("boom")).thenThrow(new IllegalStateException("bug"));
        mvc.perform(get("/requests/boom")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR")).andExpect(jsonPath("$.message").value("internal error"));
    }

    @Test
    void getAndStatusUpdate() throws Exception {
        when(service.get(ID)).thenReturn(view(RequestStatus.PENDING));
        mvc.perform(get("/requests/" + ID)).andExpect(status().isOk()).andExpect(jsonPath("$.requestId").value(ID));

        when(service.updateStatus(ID, RequestStatus.CANCELLED)).thenReturn(view(RequestStatus.CANCELLED));
        mvc.perform(put("/requests/" + ID + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void badStatusBodiesAre400() throws Exception {
        mvc.perform(put("/requests/" + ID + "/status").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("status must not be null"));
        mvc.perform(put("/requests/" + ID + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("invalid value for 'status'"));
        verify(service, never()).updateStatus(any(), any());
    }

    @Test
    void wrongMethodMediaTypeAndPath() throws Exception {
        mvc.perform(post("/requests").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/requests/" + ID))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mvc.perform(get("/nope")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}
