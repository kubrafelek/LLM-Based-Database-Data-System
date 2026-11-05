package com.llmdiscovery.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Writes JSON error bodies from servlet filters, which run outside Spring MVC's error handling. */
final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    static void write(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        MAPPER.writeValue(response.getOutputStream(), body);
    }
}
