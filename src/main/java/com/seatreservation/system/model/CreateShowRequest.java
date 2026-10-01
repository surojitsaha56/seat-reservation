package com.seatreservation.system.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** price_paise is bound as Object so floats and strings can be rejected rather than coerced. */
public record CreateShowRequest(
        String name,
        List<String> seats,
        @JsonProperty("price_paise") Object pricePaise,
        @JsonProperty("per_user_limit") Integer perUserLimit) {
}
