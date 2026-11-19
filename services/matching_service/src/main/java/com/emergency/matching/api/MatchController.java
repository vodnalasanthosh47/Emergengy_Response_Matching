package com.emergency.matching.api;

import java.time.Clock;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emergency.common.api.ErrorResponse;
import com.emergency.matching.domain.MatchOutcome;
import com.emergency.matching.domain.MatchingService;

/**
 * {@code POST /match/{requestId}}: runs the matching algorithm now and reports the result
 * (contracts/openapi/matching-service-api.yaml). Only translates the outcome into HTTP.
 */
@RestController
@RequestMapping("/match")
public class MatchController {

    private final MatchingService matching;
    private final Clock clock;

    public MatchController(MatchingService matching, Clock clock) {
        this.matching = matching;
        this.clock = clock;
    }

    @PostMapping("/{requestId}")
    public ResponseEntity<?> match(@PathVariable String requestId) {
        return switch (matching.match(requestId)) {
            case MatchOutcome.Matched m -> ResponseEntity.ok(new MatchResult(m.request().requestId(),
                    m.resource().resourceId(), Math.round(m.distanceKm() * 1000) / 1000.0, m.request(), m.resource()));
            case MatchOutcome.NoMatch n -> error(HttpStatus.UNPROCESSABLE_CONTENT, ErrorResponse.NO_MATCH,
                    "no available " + n.request().type() + " resource near the request can serve it; the request is UNMATCHED");
            case MatchOutcome.NotMatchable n -> error(HttpStatus.CONFLICT, ErrorResponse.CONFLICT,
                    "request is " + n.request().status() + "; only PENDING or UNMATCHED requests can be matched");
            case MatchOutcome.NotFound n -> error(HttpStatus.NOT_FOUND, ErrorResponse.NOT_FOUND,
                    "no request with id " + n.requestId());
        };
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message, clock));
    }
}
