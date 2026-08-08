package com.praveen.aicodingagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TEMPORARY: the security starter is on the classpath early (it pulls in
 * password encoders / filter chain scaffolding we'll need for JWT auth),
 * but until the Auth & User Service milestone lands there's no principal to
 * authenticate against. This config disables the default
 * generated-password login so the API is usable while that milestone is
 * built, and is the first thing replaced by a JWT filter chain.
 *
 * Do not ship this config as-is - it is intentionally permissive.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
