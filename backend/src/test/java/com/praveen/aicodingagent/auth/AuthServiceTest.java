package com.praveen.aicodingagent.auth;

import com.praveen.aicodingagent.auth.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private org.springframework.security.authentication.AuthenticationManager authenticationManager;

    private AuthService authService() {
        return new AuthService(userRepository, passwordEncoder, jwtService, authenticationManager);
    }

    @Test
    void rejectsRegistrationWithEmailAlreadyInUse() {
        when(userRepository.existsByEmail("praveen@example.com")).thenReturn(true);

        RegisterRequest request = new RegisterRequest("praveen@example.com", "password123", "Praveen");

        assertThatThrownBy(() -> authService().register(request))
                .isInstanceOf(EmailAlreadyInUseException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void registrationNormalizesEmailToLowercaseAndHashesPassword() {
        when(userRepository.existsByEmail("praveen@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        when(userRepository.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(java.util.UUID.randomUUID());
            return u;
        });
        when(jwtService.generateToken(any(), any())).thenReturn("token");
        when(jwtService.expirationOf("token")).thenReturn(java.time.Instant.now());

        RegisterRequest request = new RegisterRequest("Praveen@Example.com", "password123", "Praveen");
        authService().register(request);

        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("praveen@example.com");
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed");
    }
}
