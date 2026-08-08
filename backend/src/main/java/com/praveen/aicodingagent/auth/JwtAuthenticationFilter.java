package com.praveen.aicodingagent.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * A malformed/expired token is treated as "no credentials supplied", not as
 * a hard filter-level failure. This filter runs before DispatcherServlet,
 * so @RestControllerAdvice can't catch anything thrown here - if we let
 * InvalidTokenException propagate, the container's default error handling
 * returns an ugly generic 500 instead of a clean 401. Swallowing it and
 * leaving the request unauthenticated means Spring Security's own
 * AuthenticationEntryPoint (see SecurityConfig) produces the 401 for any
 * endpoint that actually requires auth - one consistent path for "you're
 * not logged in" regardless of *why*.
 */
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        extractToken(request).ifPresent(token -> {
            try {
                UUID userId = jwtService.validateAndGetUserId(token);
                userRepository.findById(userId).ifPresent(user -> {
                    UserPrincipal principal = new UserPrincipal(user);
                    var authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });
            } catch (InvalidTokenException e) {
                log.debug("Rejected request with invalid token: {}", e.getMessage());
            }
        });

        filterChain.doFilter(request, response);
    }

    private Optional<String> extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return Optional.of(header.substring(BEARER_PREFIX.length()));
        }
        return Optional.empty();
    }
}
