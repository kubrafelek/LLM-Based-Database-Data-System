package com.llmdiscovery.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Seeds the admin user at startup (a separate bean, so the call goes through the transactional proxy). */
@Component
class AdminUserInitializer implements ApplicationRunner {

    private final AuthService authService;

    AdminUserInitializer(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void run(ApplicationArguments args) {
        authService.ensureAdminUser();
    }
}
