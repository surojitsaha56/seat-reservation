package com.seatreservation.system.api;

import com.seatreservation.system.service.ShowService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShowController {
    private final ShowService service;
    private final byte[] adminToken;

    public ShowController(ShowService service, @Value("${admin.token}") String adminToken) {
        this.service = service;
        this.adminToken = adminToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/shows")
    @ResponseStatus(HttpStatus.CREATED)
    public ShowResponse create(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                               @RequestBody(required = false) CreateShowRequest req) {
        if (token == null || !MessageDigest.isEqual(adminToken, token.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.unauthorized("Missing or invalid admin token");
        }
        return service.create(req);
    }

    @GetMapping("/shows/{id}")
    public ShowResponse get(@PathVariable UUID id) {
        return service.get(id);
    }
}
