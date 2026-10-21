package com.smartlogix.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartlogix.auth.dto.AuthResponse;
import com.smartlogix.auth.dto.JwtToken;
import com.smartlogix.auth.dto.LoginRequest;
import com.smartlogix.auth.dto.PasswordResetRequest;
import com.smartlogix.auth.dto.PasswordResetResponse;
import com.smartlogix.auth.dto.RegisterRequest;
import com.smartlogix.auth.exception.BusinessRuleException;
import com.smartlogix.auth.exception.InvalidCredentialsException;
import com.smartlogix.auth.exception.UserDisabledException;
import com.smartlogix.auth.model.Role;
import com.smartlogix.auth.model.User;
import com.smartlogix.auth.repository.UserRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/*
 * Pruebas unitarias de AuthService.
 * Validan reglas criticas sin levantar Spring ni conectar MySQL.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserService userService;

    @Mock
    private JwtService jwtService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    // Guia: valida register normalizes email assigns cliente role and returns bearer token.
    @Test
    void registerNormalizesEmailAssignsClienteRoleAndReturnsBearerToken() {
        RegisterRequest request = new RegisterRequest(
                " Cliente@SmartLogix.COM ",
                "Cliente12345",
                " Cliente ",
                " Demo ");
        Role clienteRole = role("CLIENTE");
        Instant expiresAt = Instant.parse("2026-06-06T18:00:00Z");

        when(userRepository.existsByEmail("cliente@smartlogix.com")).thenReturn(false);
        when(passwordEncoder.encode("Cliente12345")).thenReturn("hash-cliente");
        when(userService.resolveRolesOrDefault(Set.of("CLIENTE"))).thenReturn(Set.of(clienteRole));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID());
            user.setCreatedAt(OffsetDateTime.now());
            user.setUpdatedAt(OffsetDateTime.now());
            return user;
        });
        when(jwtService.createToken(any(User.class))).thenReturn(new JwtToken("jwt-token", expiresAt));

        AuthResponse response = authService.register(request);

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
        assertThat(response.user().email()).isEqualTo("cliente@smartlogix.com");
        assertThat(response.user().roles()).containsExactly("CLIENTE");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("hash-cliente");
        assertThat(userCaptor.getValue().getFirstName()).isEqualTo("Cliente");
        assertThat(userCaptor.getValue().getLastName()).isEqualTo("Demo");
    }

    // Guia: valida register rejects duplicated email.
    @Test
    void registerRejectsDuplicatedEmail() {
        RegisterRequest request = new RegisterRequest(
                "cliente@smartlogix.com",
                "Cliente12345",
                "Cliente",
                "Demo");

        when(userRepository.existsByEmail("cliente@smartlogix.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Ya existe un usuario");
    }

    // Guia: valida login validates password and returns token for enabled user.
    @Test
    void loginValidatesPasswordAndReturnsTokenForEnabledUser() {
        User user = user(UUID.randomUUID(), "admin@smartlogix.com", "hash-admin", true, role("ADMIN"));
        Instant expiresAt = Instant.parse("2026-06-06T18:00:00Z");

        when(userRepository.findByEmailWithRoles("admin@smartlogix.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Admin12345", "hash-admin")).thenReturn(true);
        when(jwtService.createToken(user)).thenReturn(new JwtToken("admin-token", expiresAt));

        AuthResponse response = authService.login(new LoginRequest(" ADMIN@SmartLogix.com ", "Admin12345"));

        assertThat(response.token()).isEqualTo("admin-token");
        assertThat(response.user().roles()).containsExactly("ADMIN");
    }

    // Guia: valida login rejects invalid password.
    @Test
    void loginRejectsInvalidPassword() {
        User user = user(UUID.randomUUID(), "cliente@smartlogix.com", "hash-cliente", true, role("CLIENTE"));

        when(userRepository.findByEmailWithRoles("cliente@smartlogix.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("mala-clave", "hash-cliente")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("cliente@smartlogix.com", "mala-clave")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    // Guia: valida login rejects disabled user.
    @Test
    void loginRejectsDisabledUser() {
        User user = user(UUID.randomUUID(), "cliente@smartlogix.com", "hash-cliente", false, role("CLIENTE"));

        when(userRepository.findByEmailWithRoles("cliente@smartlogix.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Cliente12345", "hash-cliente")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("cliente@smartlogix.com", "Cliente12345")))
                .isInstanceOf(UserDisabledException.class);
    }

    // Guia: valida reset password stores encoded password.
    @Test
    void resetPasswordStoresEncodedPassword() {
        User user = user(UUID.randomUUID(), "cliente@smartlogix.com", "hash-antiguo", true, role("CLIENTE"));

        when(userRepository.findByEmail("cliente@smartlogix.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("NuevaClave123")).thenReturn("hash-nuevo");

        PasswordResetResponse response = authService.resetPassword(
                new PasswordResetRequest(" CLIENTE@SmartLogix.com ", "NuevaClave123"));

        assertThat(response.message()).contains("Clave actualizada");
        assertThat(user.getPasswordHash()).isEqualTo("hash-nuevo");
        verify(userRepository).save(user);
    }

    private User user(UUID id, String email, String passwordHash, boolean enabled, Role role) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setFirstName("Nombre");
        user.setLastName("Apellido");
        user.setEnabled(enabled);
        user.setRoles(Set.of(role));
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        return user;
    }

    private Role role(String name) {
        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setName(name);
        role.setCreatedAt(OffsetDateTime.now());
        role.setUpdatedAt(OffsetDateTime.now());
        return role;
    }
}
