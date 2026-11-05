package com.llmdiscovery.common;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

@RestController
public class RootController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /** Browser UI over the same /api/v1 endpoints (same origin, so no CORS needed). */
    @Hidden
    @GetMapping("/")
    public ResponseEntity<Void> root() {
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).header("Location", "/ui/").build();
    }

    @Hidden
    @GetMapping({"/ui", "/ui/"})
    public ModelAndView ui() {
        return new ModelAndView("forward:/ui/index.html");
    }
}
