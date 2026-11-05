package com.llmdiscovery.classification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * JSON text formatted like Python's json.dumps(..., ensure_ascii=False): ", " and ": " separators.
 * Used for the prompt so the LLM sees byte-for-byte the same input as from the Python service.
 */
final class PyJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PyJson() {}

    static String string(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String stringList(List<String> values) {
        return values.stream().map(PyJson::string).collect(Collectors.joining(", ", "[", "]"));
    }

    static String stringMap(Map<String, String> values) {
        return values.entrySet().stream()
                .map(e -> string(e.getKey()) + ": " + string(e.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
    }
}
