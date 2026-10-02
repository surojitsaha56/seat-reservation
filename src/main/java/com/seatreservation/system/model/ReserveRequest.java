package com.seatreservation.system.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Any user_id in the body is deliberately not bound: identity comes only from the token. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReserveRequest(
        List<String> seats,
        @JsonProperty("idempotency_key") String idempotencyKey) {
}
