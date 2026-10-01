CREATE TABLE shows (
    id              uuid PRIMARY KEY,
    name            text        NOT NULL CHECK (length(btrim(name)) > 0),
    price_paise     bigint      NOT NULL CHECK (price_paise > 0),
    per_user_limit  int         NOT NULL DEFAULT 4 CHECK (per_user_limit >= 1),
    total_seats     int         NOT NULL CHECK (total_seats > 0),
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    id              uuid PRIMARY KEY,
    show_id         uuid        NOT NULL REFERENCES shows (id),
    user_id         text        NOT NULL,
    idempotency_key text        NOT NULL,
    request_hash    text        NOT NULL,
    seats           text[]      NOT NULL,
    amount_paise    bigint      NOT NULL CHECK (amount_paise >= 0),
    status          text        NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reservations_user_idem_uq UNIQUE (user_id, idempotency_key)
);
CREATE INDEX reservations_show_idx ON reservations (show_id);

-- ord preserves the order seats were supplied at show creation (used for display).
CREATE TABLE seats (
    show_id        uuid NOT NULL REFERENCES shows (id),
    label          text NOT NULL,
    ord            int  NOT NULL,
    status         text NOT NULL DEFAULT 'available'
                        CHECK (status IN ('available', 'held', 'confirmed')),
    reservation_id uuid NULL REFERENCES reservations (id),
    user_id        text NULL,
    PRIMARY KEY (show_id, label)
);
CREATE INDEX seats_reservation_idx ON seats (reservation_id) WHERE reservation_id IS NOT NULL;
CREATE INDEX seats_show_status_idx ON seats (show_id, status);

CREATE TABLE user_show_holds (
    show_id    uuid NOT NULL REFERENCES shows (id),
    user_id    text NOT NULL,
    seat_count int  NOT NULL CHECK (seat_count >= 0),
    PRIMARY KEY (show_id, user_id)
);
