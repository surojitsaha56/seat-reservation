package com.seatreservation.system.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        @JsonProperty("reservation_id") UUID reservationId,
        @JsonProperty("show_id") UUID showId,
        @JsonProperty("user_id") String userId,
        List<String> seats,
        @JsonProperty("amount_paise") long amountPaise,
        String status) {
}
