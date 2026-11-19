package com.emergency.resource.service;

import static com.emergency.common.resource.ResourceStatus.AVAILABLE;
import static com.emergency.common.resource.ResourceStatus.DEPLETED;
import static com.emergency.common.resource.ResourceStatus.OFFLINE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.emergency.common.geo.GeoHash;
import com.emergency.common.model.Category;
import com.emergency.common.model.Location;
import com.emergency.common.resource.Capacity;
import com.emergency.common.resource.Provider;
import com.emergency.common.resource.ProviderKind;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.common.resource.ResourceView;
import com.emergency.common.storage.StorageKeys;
import com.emergency.resource.config.ResourceServiceProperties;
import com.emergency.resource.exception.DependencyUnavailableException;
import com.emergency.resource.exception.InvalidResourceException;
import com.emergency.resource.exception.ResourceConflictException;
import com.emergency.resource.exception.ResourceNotFoundException;
import com.emergency.resource.support.InMemoryResourceRepository;

import tools.jackson.databind.json.JsonMapper;

class ResourceServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00.123456Z");

    private final InMemoryResourceRepository repo = new InMemoryResourceRepository();
    private final ResourceService service = new ResourceService(repo, new ResourceValidator(),
            Clock.fixed(NOW, ZoneOffset.UTC), new ResourceServiceProperties("http://unused", Duration.ofSeconds(1), 6));
    private final JsonMapper json = JsonMapper.builder().build();

    private NewResource water(String total) {
        return new NewResource(Category.WATER, new Location(12.9716, 77.5946),
                new Provider(" Akshaya NGO ", ProviderKind.NGO, "  "),
                new Capacity(new BigDecimal(total), null, null), null);
    }

    @Test
    void registerComputesIdCellStatusAndStores() {
        ResourceView v = service.register(water("500"));

        assertThat(v.geoCell()).isEqualTo("tdr1v9").isEqualTo(GeoHash.encode(12.9716, 77.5946, 6));
        assertThat(v.resourceId()).matches("res_tdr1v9_[0-9a-f-]{36}");
        assertThat(StorageKeys.resourceIdToKey(v.resourceId())).startsWith("resource:tdr1v9:");
        assertThat(v.status()).isEqualTo(AVAILABLE);
        assertThat(v.capacity()).isEqualTo(new Capacity(new BigDecimal("500"), new BigDecimal("500"), "L"));
        assertThat(v.details()).isEqualTo(json.readTree("{}"));
        assertThat(v.provider()).isEqualTo(new Provider("Akshaya NGO", ProviderKind.NGO, null));
        assertThat(v.createdAt()).isEqualTo(v.updatedAt()).isEqualTo(Instant.parse("2026-11-19T10:00:00.123Z"));
        assertThat(v.version()).isEqualTo(1);
        assertThat(repo.records).containsKey(v.resourceId());
    }

    @Test
    void registerRejectsMissingBasics() {
        NewResource ok = water("10");
        assertThatThrownBy(() -> service.register(new NewResource(null, ok.location(), ok.provider(), ok.capacity(), null)))
                .isInstanceOf(InvalidResourceException.class).hasMessage("type is required");
        assertThatThrownBy(() -> service.register(new NewResource(ok.type(), new Location(91.0, 0.0), ok.provider(), ok.capacity(), null)))
                .isInstanceOf(InvalidResourceException.class).hasMessageContaining("location");
        assertThatThrownBy(() -> service.register(new NewResource(ok.type(), ok.location(), new Provider("x", null, null), ok.capacity(), null)))
                .isInstanceOf(InvalidResourceException.class).hasMessageContaining("provider");
        assertThat(repo.records).isEmpty();
    }

    @Test
    void storageDownIsUnavailable() {
        repo.down = true;
        assertThatThrownBy(() -> service.register(water("10"))).isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void getFindsAndRejectsBadIds() {
        ResourceView v = service.register(water("10"));
        assertThat(service.get(v.resourceId())).isEqualTo(v);
        assertThatThrownBy(() -> service.get("res_tdr1v9_00000000-0000-0000-0000-000000000000"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get("tdr1v9_abc")).isInstanceOf(InvalidResourceException.class);
        assertThatThrownBy(() -> service.get("res_TDR1V9_abc")).isInstanceOf(InvalidResourceException.class);
        assertThatThrownBy(() -> service.get("res_tdr1v9_a/b")).isInstanceOf(InvalidResourceException.class);
    }

    @Test
    void listFiltersAndChecksLimit() {
        ResourceView a = service.register(water("10"));
        service.register(new NewResource(Category.FOOD, new Location(12.97, 77.59),
                new Provider("Depot", ProviderKind.GOVT, null), new Capacity(new BigDecimal("50"), null, null), null));
        assertThat(service.list(Category.WATER, null, 50)).containsExactly(a);
        assertThat(service.list(null, AVAILABLE, 50)).hasSize(2);
        assertThat(service.list(null, OFFLINE, 50)).isEmpty();
        assertThatThrownBy(() -> service.list(null, null, 0)).isInstanceOf(InvalidResourceException.class);
        assertThatThrownBy(() -> service.list(null, null, 1001)).isInstanceOf(InvalidResourceException.class);
    }

    private ResourceView stored(ResourceStatus status, String remaining) {
        ResourceView v = service.register(water("100"));
        ResourceDocument d = repo.records.get(v.resourceId()).document();
        repo.put(new ResourceDocument(d.resourceId(), d.type(), d.provider(), d.location(), d.geoCell(),
                new Capacity(d.capacity().total(), new BigDecimal(remaining), d.capacity().unit()), d.details(),
                status, d.createdAt(), d.updatedAt()), 3);
        return v;
    }

    @ParameterizedTest
    @CsvSource({ "AVAILABLE, OFFLINE", "OFFLINE, AVAILABLE", "DEPLETED, OFFLINE" })
    void legalClientTransitions(ResourceStatus from, ResourceStatus to) {
        String id = stored(from, from == DEPLETED ? "0" : "40").resourceId();
        ResourceView updated = service.updateStatus(id, to);
        assertThat(updated.status()).isEqualTo(to);
        assertThat(updated.version()).isEqualTo(4);
    }

    @Test
    void sameStatusIsANoOp() {
        String id = stored(OFFLINE, "40").resourceId();
        assertThat(service.updateStatus(id, OFFLINE).version()).isEqualTo(3);
        assertThat(repo.updates).isZero();
    }

    @Test
    void depletedIsReservedForMatching() {
        String id = stored(AVAILABLE, "40").resourceId();
        assertThatThrownBy(() -> service.updateStatus(id, DEPLETED)).isInstanceOf(InvalidResourceException.class);
        assertThatThrownBy(() -> service.updateStatus(id, null)).isInstanceOf(InvalidResourceException.class);
    }

    @Test
    void cannotMakeAnEmptyResourceAvailable() {
        String depleted = stored(DEPLETED, "0").resourceId();
        String offlineEmpty = stored(OFFLINE, "0").resourceId();
        assertThatThrownBy(() -> service.updateStatus(depleted, AVAILABLE))
                .isInstanceOf(ResourceConflictException.class).hasMessage("resource has no remaining capacity");
        assertThatThrownBy(() -> service.updateStatus(offlineEmpty, AVAILABLE))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void updateOfUnknownResourceIsNotFound() {
        assertThatThrownBy(() -> service.updateStatus("res_tdr1v9_00000000-0000-0000-0000-000000000000", OFFLINE))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void concurrentChangeIsReReadAndDecidedAgain() {
        ResourceView v = stored(AVAILABLE, "40");
        ResourceDocument before = repo.records.get(v.resourceId()).document();
        // A claim by the Matching Service lands between our read and our write.
        repo.beforeUpdate = id -> {
            repo.beforeUpdate = x -> { };
            repo.put(before, 4);
        };
        assertThat(service.updateStatus(v.resourceId(), OFFLINE).version()).isEqualTo(5);
        assertThat(repo.updates).isEqualTo(1);
    }

    @Test
    void givesUpAfterRepeatedConflicts() {
        ResourceView v = stored(AVAILABLE, "40");
        ResourceDocument before = repo.records.get(v.resourceId()).document();
        repo.beforeUpdate = id -> repo.put(before, repo.records.get(id).version() + 1);
        assertThatThrownBy(() -> service.updateStatus(v.resourceId(), OFFLINE))
                .isInstanceOf(ResourceConflictException.class).hasMessageContaining("concurrently");
    }
}
