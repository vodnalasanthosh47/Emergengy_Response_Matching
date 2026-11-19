package com.emergency.matching.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.emergency.common.geo.GeoDistance;
import com.emergency.common.model.Category;
import com.emergency.common.request.RequestDocument;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;

/**
 * Which resources can serve a request, and in which order to try them (spec §5 steps 3–4).
 * Kept in one class on purpose: the scoring is the part most likely to change (e.g. to
 * weigh distance against capacity, or provider kind).
 *
 * <ul>
 * <li>Fits: same type, AVAILABLE, {@code capacity.remaining >= amount}; MEDICINE also the
 *     same medicine name (trimmed, case-insensitive).</li>
 * <li>Order: nearest first (haversine); equal distance → most remaining capacity (keeps
 *     small resources for small requests); then resource ID, so the order is deterministic.</li>
 * </ul>
 */
public class CandidateRanking {

    /** A resource that fits, with its distance from the request. */
    public record Candidate(Versioned<ResourceDocument> resource, double distanceKm) {
    }

    private static final Comparator<Candidate> ORDER = Comparator
            .comparingDouble(Candidate::distanceKm)
            .thenComparing((Candidate c) -> c.resource().document().capacity().remaining(), Comparator.reverseOrder())
            .thenComparing(c -> c.resource().document().resourceId());

    public List<Candidate> rank(RequestDocument request, Demand demand, List<Versioned<ResourceDocument>> resources) {
        return resources.stream()
                .filter(r -> fits(r.document(), request.type(), demand))
                .map(r -> new Candidate(r, GeoDistance.km(request.location(), r.document().location())))
                .sorted(ORDER)
                .toList();
    }

    public boolean fits(ResourceDocument resource, Category type, Demand demand) {
        if (resource.type() != type || resource.status() != ResourceStatus.AVAILABLE) {
            return false;
        }
        if (resource.capacity() == null || resource.capacity().remaining() == null
                || resource.capacity().remaining().compareTo(demand.amount()) < 0) {
            return false;
        }
        if (type == Category.MEDICINE) {
            String offered = resource.details() == null ? null : resource.details().path("name").asString(null);
            return offered != null && demand.medicineName() != null && normalise(offered).equals(normalise(demand.medicineName()));
        }
        return true;
    }

    private static String normalise(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
