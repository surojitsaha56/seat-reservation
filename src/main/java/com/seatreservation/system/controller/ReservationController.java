package com.seatreservation.system.controller;

import com.seatreservation.system.auth.AuthContext;
import com.seatreservation.system.exception.ApiException;
import com.seatreservation.system.model.ReservationResponse;
import com.seatreservation.system.model.ReserveRequest;
import com.seatreservation.system.model.ReserveResult;
import com.seatreservation.system.service.ReservationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {
    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    /**
     * 201 on first success, 200 when the same (user, Idempotency-Key, request) replays the original
     * reservation. Both return the same JSON body shape.
     */
    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
            @RequestBody(required = false) ReserveRequest body,
            HttpServletRequest request) {

        // checks jwt token and verifies the user.
        String userId = AuthContext.userId(request);
        MDC.put("show_id", id.toString());

        // validating idempotency key in header and body
        if (body == null) throw ApiException.validation("Request body is required");
        String bodyKey = body.idempotencyKey();
        boolean hasHeader = headerKey != null && !headerKey.isBlank();
        boolean hasBody = bodyKey != null && !bodyKey.isBlank();
        if (hasHeader && hasBody && !headerKey.equals(bodyKey)) {
            throw ApiException.validation("Idempotency-Key header and idempotency_key body field differ");
        }
        String key = hasHeader ? headerKey : bodyKey;

        // calls reserve function
        ReserveResult r = service.reserve(id, userId, key, body.seats());
        HttpStatus status = r.outcome() == ReserveResult.Outcome.REPLAY ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(r.reservation());
    }
}
