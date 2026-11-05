package com.llmdiscovery.auth;

import com.llmdiscovery.common.AppException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "auth")
public class AuthController {

    public record LoginRequest(
            @NotNull @Size(min = 1, max = 150) String username,
            @NotNull @Size(min = 1, max = 1024) String password) {}

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}

    private final AuthService authService;
    private final JwtService jwt;

    public AuthController(AuthService authService, JwtService jwt) {
        this.authService = authService;
        this.jwt = jwt;
    }

    /** /auth is the case study PDF path; /api/v1/auth the documented one. */
    @Operation(summary = "Exchange credentials (JSON body or HTTP Basic) for a short-lived Bearer token")
    @PostMapping({"/api/v1/auth", "/auth"})
    public TokenResponse issueToken(
            @Valid @RequestBody(required = false) LoginRequest body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String username;
        String password;
        if (body != null) {
            username = body.username();
            password = body.password();
        } else {
            Optional<BasicCredentials> basic = BasicCredentials.parse(authorization);
            if (basic.isEmpty()) {
                throw new AppException.Unauthorized(
                        "Provide credentials as a JSON body or with HTTP Basic authentication", "Basic");
            }
            username = basic.get().username();
            password = basic.get().password();
        }
        if (!authService.authenticate(username, password)) {
            throw new AppException.Unauthorized("Invalid credentials", "Basic");
        }
        JwtService.Token token = jwt.create(username);
        return new TokenResponse(token.value(), "bearer", token.expiresIn());
    }
}
