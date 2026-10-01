package com.seatreservation.system.service;

import com.seatreservation.system.api.ApiException;
import com.seatreservation.system.api.CountsDto;
import com.seatreservation.system.api.CreateShowRequest;
import com.seatreservation.system.api.SeatDto;
import com.seatreservation.system.api.ShowResponse;
import com.seatreservation.system.repo.ShowRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {
    static final int DEFAULT_PER_USER_LIMIT = 4;

    private final ShowRepository repo;

    public ShowService(ShowRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public ShowResponse create(CreateShowRequest req) {
        if (req == null) throw ApiException.validation("Request body is required");
        if (req.name() == null || req.name().isBlank()) throw ApiException.validation("name must be non-blank");
        if (req.seats() == null || req.seats().isEmpty()) throw ApiException.validation("seats must be non-empty");
        Set<String> seen = new HashSet<>();
        for (String s : req.seats()) {
            if (s == null || s.isBlank()) throw ApiException.validation("seat labels must be non-blank");
            if (!seen.add(s)) throw ApiException.validation("duplicate seat label: " + s);
        }
        if (!(req.pricePaise() instanceof Integer || req.pricePaise() instanceof Long)
                || ((Number) req.pricePaise()).longValue() <= 0) {
            throw ApiException.validation("price_paise must be an integer > 0");
        }
        long price = ((Number) req.pricePaise()).longValue();
        int limit = req.perUserLimit() == null ? DEFAULT_PER_USER_LIMIT : req.perUserLimit();
        if (limit < 1) throw ApiException.validation("per_user_limit must be >= 1");

        UUID id = UUID.randomUUID();
        List<String> labels = req.seats();
        repo.insertShow(id, req.name().trim(), price, limit, labels.size());
        repo.insertSeats(id, labels);
        List<SeatDto> seats = labels.stream().map(l -> new SeatDto(l, "available")).toList();
        return new ShowResponse(id, req.name().trim(), price, limit, labels.size(), null, seats);
    }

    /** REPEATABLE READ so the counts query and the seat list see the same snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ShowResponse get(UUID id) {
        var show = repo.findShow(id).orElseThrow(() -> ApiException.notFound("show not found: " + id));
        CountsDto counts = repo.countSeats(id);
        List<SeatDto> seats = repo.findSeats(id);
        return new ShowResponse(show.id(), show.name(), show.pricePaise(), show.perUserLimit(),
                show.totalSeats(), counts, seats);
    }
}
