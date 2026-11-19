package com.emergency.resource.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
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
import com.emergency.common.resource.Capacity;
import com.emergency.common.resource.Provider;
import com.emergency.common.resource.ProviderKind;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.common.resource.ResourceView;
import com.emergency.resource.exception.DependencyUnavailableException;
import com.emergency.resource.exception.InvalidResourceException;
import com.emergency.resource.exception.ResourceConflictException;
import com.emergency.resource.exception.ResourceNotFoundException;
import com.emergency.resource.service.NewResource;
import com.emergency.resource.service.ResourceService;

import tools.jackson.databind.json.JsonMapper;

/** The HTTP layer with the real Jackson and validation settings, and the service mocked. */
@WebMvcTest(ResourceController.class)
@Import(ResourceControllerTest.FixedClock.class)
class ResourceControllerTest {

    static final String ID = "res_tdr1v9_8a7c3e0e-4c1d-4b9a-9d55-0d3c2f1a9b77";

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ResourceService service;

    private final JsonMapper json = JsonMapper.builder().build();

    private ResourceView view(ResourceStatus status) {
        return new ResourceView(ID, Category.WATER, new Provider("Akshaya NGO", ProviderKind.NGO, null),
                new Location(12.97, 77.59), "tdr1v9", new Capacity(new BigDecimal("500"), new BigDecimal("480"), "L"),
                json.readTree("{}"), status, Instant.parse("2026-11-19T10:00:00.250Z"),
                Instant.parse("2026-11-19T10:00:00.250Z"), 2);
    }

    private static final String VALID = """
            {"type":"WATER","location":{"lat":12.97,"lng":77.59},
             "provider":{"name":"Akshaya NGO","kind":"NGO"},"capacity":{"total":500,"remaining":480}}""";

    @Test
    void registerReturns201WithLocationAndTheResource() throws Exception {
        when(service.register(any())).thenReturn(view(ResourceStatus.AVAILABLE));
        mvc.perform(post("/resources").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/resources/" + ID))
                .andExpect(jsonPath("$.resourceId").value(ID))
                .andExpect(jsonPath("$.geoCell").value("tdr1v9"))
                .andExpect(jsonPath("$.capacity.total").value(500))
                .andExpect(jsonPath("$.capacity.remaining").value(480))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.version").value(2));

        ArgumentCaptor<NewResource> sent = ArgumentCaptor.forClass(NewResource.class);
        verify(service).register(sent.capture());
        NewResource cmd = sent.getValue();
        assertThat(cmd.capacity().remaining()).isEqualByComparingTo("480");
        assertThat(cmd.capacity().unit()).isNull();
        assertThat(cmd.provider().kind()).isEqualTo(ProviderKind.NGO);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"type\":\"WATER\",\"location\":{\"lat\":12.97,\"lng\":77.59},\"provider\":{\"name\":\"a\",\"kind\":\"NGO\"},\"capacity\":{\"total\":5},\"geoCell\":\"tdr1v9\"} | unknown field 'geoCell'",
            "{\"type\":\"WATER\",\"location\":{\"lat\":12.97,\"lng\":77.59},\"provider\":{\"name\":\"a\",\"kind\":\"NGO\"},\"capacity\":{\"total\":5},\"status\":\"OFFLINE\"} | unknown field 'status'",
            "{\"type\":\"WATER\",\"location\":{\"lat\":12.97,\"lng\":77.59},\"provider\":{\"name\":\"a\",\"kind\":\"NGO\"},\"capacity\":{\"total\":5,\"remainig\":3}} | unknown field 'remainig'",
            "{\"type\":\"water\",\"location\":{\"lat\":12.97,\"lng\":77.59},\"provider\":{\"name\":\"a\",\"kind\":\"NGO\"},\"capacity\":{\"total\":5}} | invalid value for 'type'",
            "{\"type\":\"WATER\",\"location\":{\"lat\":12.97,\"lng\":77.59},\"provider\":{\"name\":\"a\",\"kind\":\"CHARITY\"},\"capacity\":{\"total\":5}} | invalid value for 'provider.kind'",
            "{\"type\":\"WATER\",\"location\":{\"lat\":12.97,\"lng\":77.59},\"provider\":{\"name\":\"a\",\"kind\":\"NGO\"},\"capacity\":{\"total\":\"lots\"}} | invalid value for 'capacity.total'",
            "{\"type\":\"WATER\",\"location\":{\"lat\":95,\"lng\":77.59},\"provider\":{\"name\":\"a\",\"kind\":\"NGO\"},\"capacity\":{\"total\":5}} | location.lat must be less than or equal to 90",
            "{\"type\":\"WATER\",\"location\":{\"lat\":12.97,\"lng\":77.59},\"provider\":{\"name\":\" \",\"kind\":\"NGO\"},\"capacity\":{\"total\":5}} | provider.name must not be blank",
            "{\"type\":\"WATER\",\"location\":{\"lat\":12.97,\"lng\":77.59},\"provider\":{\"name\":\"a\",\"kind\":\"NGO\"},\"capacity\":{}} | capacity.total must not be null",
            "{\"location\":{\"lat\":12.97,\"lng\":77.59},\"provider\":{\"name\":\"a\",\"kind\":\"NGO\"},\"capacity\":{\"total\":5}} | type must not be null",
            "[] | request body must be a JSON object",
    })
    void badBodiesAre400AndNeverReachTheService(String body, String message) throws Exception {
        mvc.perform(post("/resources").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.timestamp").value("2026-11-19T10:00:00Z"));
        verify(service, never()).register(any());
    }

    @Test
    void listPassesFiltersAndDefaultLimit() throws Exception {
        when(service.list(Category.WATER, ResourceStatus.AVAILABLE, 50)).thenReturn(List.of(view(ResourceStatus.AVAILABLE)));
        mvc.perform(get("/resources").param("type", "WATER").param("status", "AVAILABLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources.length()").value(1))
                .andExpect(jsonPath("$.resources[0].resourceId").value(ID));
    }

    @Test
    void listWithoutFilters() throws Exception {
        when(service.list(null, null, 5)).thenReturn(List.of());
        mvc.perform(get("/resources").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({ "type, water", "status, BUSY", "limit, abc" })
    void badQueryParametersAre400(String name, String value) throws Exception {
        mvc.perform(get("/resources").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid value for '" + name + "'"));
    }

    @Test
    void getAndStatusUpdate() throws Exception {
        when(service.get(ID)).thenReturn(view(ResourceStatus.AVAILABLE));
        when(service.updateStatus(eq(ID), eq(ResourceStatus.OFFLINE))).thenReturn(view(ResourceStatus.OFFLINE));
        mvc.perform(get("/resources/" + ID)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AVAILABLE"));
        mvc.perform(put("/resources/" + ID + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OFFLINE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFLINE"));
    }

    @Test
    void serviceErrorsMapToStatusCodes() throws Exception {
        when(service.get("res_x_1")).thenThrow(new InvalidResourceException("resourceId must look like res_{geoCell}_{uuid}"));
        when(service.get("res_tdr1v9_404")).thenThrow(new ResourceNotFoundException("no resource with id res_tdr1v9_404"));
        when(service.get("res_tdr1v9_503")).thenThrow(new DependencyUnavailableException("coordinator down", null));
        when(service.get("res_tdr1v9_500")).thenThrow(new IllegalStateException("secret detail"));
        when(service.updateStatus(ID, ResourceStatus.AVAILABLE)).thenThrow(new ResourceConflictException("resource has no remaining capacity"));

        mvc.perform(get("/resources/res_x_1")).andExpect(status().isBadRequest());
        mvc.perform(get("/resources/res_tdr1v9_404")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mvc.perform(get("/resources/res_tdr1v9_503")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("a dependency is unavailable, retry later"));
        mvc.perform(get("/resources/res_tdr1v9_500")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("internal error"));
        mvc.perform(put("/resources/" + ID + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"AVAILABLE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void statusBodyIsValidated() throws Exception {
        mvc.perform(put("/resources/" + ID + "/status").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("status must not be null"));
        mvc.perform(put("/resources/" + ID + "/status").contentType(MediaType.TEXT_PLAIN).content("OFFLINE"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/resources/" + ID)).andExpect(status().isMethodNotAllowed());
    }
}
