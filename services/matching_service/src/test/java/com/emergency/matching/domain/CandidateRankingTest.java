package com.emergency.matching.domain;

import static com.emergency.matching.support.Fixtures.LAT;
import static com.emergency.matching.support.Fixtures.LNG;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.emergency.common.model.Category;
import com.emergency.common.request.RequestDocument;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.matching.domain.CandidateRanking.Candidate;
import com.emergency.matching.support.Fixtures;

class CandidateRankingTest {

    private final CandidateRanking ranking = new CandidateRanking();
    private final RequestDocument water20 = Fixtures.request(Category.WATER, "{\"quantity\":20,\"unit\":\"L\"}");

    private List<String> ranked(RequestDocument request, ResourceDocument... resources) {
        return ranking.rank(request, Demand.of(request), java.util.Arrays.stream(resources).map(r -> new Versioned<>(r, 1)).toList())
                .stream().map(c -> c.resource().document().resourceId()).toList();
    }

    @Test
    void nearestFittingResourceFirst() {
        ResourceDocument far = Fixtures.resource(Category.WATER, 0.004, "500", "500");
        ResourceDocument near = Fixtures.resource(Category.WATER, 0.001, "500", "500");
        ResourceDocument nearButTooSmall = Fixtures.resource(Category.WATER, 0.0005, "500", "19");
        assertThat(ranked(water20, far, nearButTooSmall, near)).containsExactly(near.resourceId(), far.resourceId());
    }

    @Test
    void exactlyEnoughFits() {
        ResourceDocument exact = Fixtures.resource(Category.WATER, 0.001, "20", "20");
        assertThat(ranked(water20, exact)).containsExactly(exact.resourceId());
    }

    @Test
    void equalDistanceMoreRemainingFirstThenById() {
        ResourceDocument small = Fixtures.resource(Category.WATER, LAT, LNG, "100", "50", ResourceStatus.AVAILABLE, null);
        ResourceDocument big = Fixtures.resource(Category.WATER, LAT, LNG, "500", "400", ResourceStatus.AVAILABLE, null);
        ResourceDocument big2 = Fixtures.resource(Category.WATER, LAT, LNG, "500", "400", ResourceStatus.AVAILABLE, null);
        List<String> order = ranked(water20, small, big2, big);
        assertThat(order.get(2)).isEqualTo(small.resourceId());
        assertThat(order.subList(0, 2)).isSorted();
    }

    @Test
    void onlySameTypeAndAvailable() {
        ResourceDocument food = Fixtures.resource(Category.FOOD, 0.001, "500", "500");
        ResourceDocument offline = Fixtures.resource(Category.WATER, LAT, LNG, "500", "500", ResourceStatus.OFFLINE, null);
        ResourceDocument depleted = Fixtures.resource(Category.WATER, LAT, LNG, "500", "0", ResourceStatus.DEPLETED, null);
        assertThat(ranked(water20, food, offline, depleted)).isEmpty();
    }

    @Test
    void medicineMustBeTheSameMedicineIgnoringCase() {
        RequestDocument insulin = Fixtures.request(Category.MEDICINE, "{\"name\":\"Insulin\",\"quantity\":2}");
        ResourceDocument same = Fixtures.resource(Category.MEDICINE, LAT, LNG, "10", "10", ResourceStatus.AVAILABLE, " insulin ");
        ResourceDocument other = Fixtures.resource(Category.MEDICINE, LAT, LNG, "10", "10", ResourceStatus.AVAILABLE, "Paracetamol");
        assertThat(ranked(insulin, other, same)).containsExactly(same.resourceId());
    }

    @Test
    void distanceIsReported() {
        ResourceDocument r = Fixtures.resource(Category.WATER, 0.009, "500", "500"); // ~1 km north
        Candidate c = ranking.rank(water20, Demand.of(water20), List.of(new Versioned<>(r, 1))).get(0);
        assertThat(c.distanceKm()).isBetween(0.99, 1.01);
    }
}
