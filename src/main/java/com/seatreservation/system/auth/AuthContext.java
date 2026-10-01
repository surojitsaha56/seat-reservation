package com.seatreservation.system.auth;

import com.seatreservation.system.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;

/** Access to the authenticated user id, which comes only from the verified JWT subject. */
public final class AuthContext {
    public static final String USER_ID_ATTR = "auth.userId";

    private AuthContext() {}

    public static String userId(HttpServletRequest request) {
        Object v = request.getAttribute(USER_ID_ATTR);
        if (v instanceof String s) {
            return s;
        }
        throw ApiException.unauthorized("Authentication required");
    }
}
