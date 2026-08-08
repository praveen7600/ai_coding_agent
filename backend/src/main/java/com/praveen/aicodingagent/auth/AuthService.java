package com.praveen.aicodingagent.auth;

import com.praveen.aicodingagent.auth.dto.AuthResponse;
import com.praveen.aicodingagent.auth.dto.LoginRequest;
import com.praveen.aicodingagent.auth.dto.RegisterRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String normalizedEmail = request.email().toLowerCase();
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyInUseException(normalizedEmail);
        }

        User user = User.builder()
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(request.password()))
                .displayName(request.displayName())
                .build();
        user = userRepository.save(user);

        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        // Delegates to Spring Security's AuthenticationManager (DaoAuthenticationProvider
        // under the hood) rather than hand-rolling password comparison here - this is
        // the same machinery Spring Security uses everywhere else, so it stays consistent
        // if e.g. account lockout policies get added later.
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email().toLowerCase(), request.password())
        );

        User user = userRepository.findByEmail(request.email().toLowerCase())
                .orElseThrow(() -> new IllegalStateException("Authenticated user vanished mid-request"));

        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(
                token,
                jwtService.expirationOf(token),
                user.getId(),
                user.getEmail(),
                user.getDisplayName()
        );
    }
}
