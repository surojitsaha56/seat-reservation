package com.seatreservation.system.model;

/**
 * Outcome of a successful reserve call. A decline is carried by ReserveDeclinedException (reason inside);
 * step 6 metrics can hook the single outcome log point in ReservationService.
 */
public record ReserveResult(Outcome outcome, ReservationResponse reservation) {
    public enum Outcome { CONFIRMED, REPLAY, DECLINED }

    public static ReserveResult confirmed(ReservationResponse r) {
        return new ReserveResult(Outcome.CONFIRMED, r);
    }

    public static ReserveResult replay(ReservationResponse r) {
        return new ReserveResult(Outcome.REPLAY, r);
    }
}
