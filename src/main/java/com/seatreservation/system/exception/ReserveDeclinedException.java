package com.seatreservation.system.exception;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A domain decline of a reserve request (HTTP 409). Thrown inside the transaction callback so that
 * everything, including the idempotency row, is rolled back.
 */
public class ReserveDeclinedException extends ApiException {
    public enum Reason {
        SEAT_TAKEN("seat_taken"),
        PER_USER_LIMIT("per_user_limit"),
        IDEMPOTENCY_KEY_REUSED("idempotency_key_reused");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Reason reason;
    private final List<String> seats;

    public ReserveDeclinedException(Reason reason, String message, List<String> seats) {
        super(HttpStatus.CONFLICT, "conflict", message);
        this.reason = reason;
        this.seats = seats;
    }

    public Reason reason() { return reason; }

    /** Unavailable seats (seat_taken only), otherwise empty. */
    public List<String> seats() { return seats; }
}
