package com.smartlogix.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartlogix.auth.dto.RoleUpdateRequest;
import com.smartlogix.auth.dto.UserCreateRequest;
import com.smartlogix.auth.dto.UserResponse;
import com.smartlogix.auth.dto.UserStatusUpdateRequest;
import com.smartlogix.auth.dto.UserUpdateRequest;
import com.smartlogix.auth.exception.BusinessRuleException;
import com.smartlogix.auth.exception.ResourceNotFoundException;
import com.smartlogix.auth.model.Role;
import com.smartlogix.auth.model.User;
import com.smartlogix.auth.repository.RoleRepository;
import com.smartlogix.auth.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
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
 * Pruebas unitarias de UserService.
 * Cubren administracion de usuarios y resolucion de roles.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    // Guia: valida create normalizes email uses default cliente role and encodes password.
    @Test
    void createNormalizesEmailUsesDefaultClienteRoleAndEncodesPassword() {
        Role cliente = role("CLIENTE");
        UserCreateRequest request = new UserCreateRequest(
                " Nuevo@SmartLogix.COM ",
                "Cliente12345",
                " Nuevo ",
                " Usuario ",
                Set.of(),
                null);

        when(userRepository.existsByEmail("nuevo@smartlogix.com")).thenReturn(false);
        when(roleRepository.findByNameIn(anyCollection())).thenReturn(List.of(cliente));
        when(passwordEncoder.encode("Cliente12345")).thenReturn("hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID());
            user.setCreatedAt(OffsetDateTime.now());
            user.setUpdatedAt(OffsetDateTime.now());
            return user;
        });

        UserResponse response = userService.create(request);

        assertThat(response.email()).isEqualTo("nuevo@smartlogix.com");
        assertThat(response.roles()).containsExactly("CLIENTE");
        assertThat(response.enabled()).isTrue();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("hash");
        assertThat(userCaptor.getValue().getFirstName()).isEqualTo("Nuevo");
    }

    // Guia: valida create rejects duplicated email.
    @Test
    void createRejectsDuplicatedEmail() {
        UserCreateRequest request = new UserCreateRequest(
                "duplicado@smartlogix.com",
                "Cliente12345",
                "Duplicado",
                "Demo",
                Set.of("CLIENTE"),
                true);

        when(userRepository.existsByEmail("duplicado@smartlogix.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Ya existe un usuario");
    }

    // Guia: valida update rejects email already used by another user.
    @Test
    void updateRejectsEmailAlreadyUsedByAnotherUser() {
        UUID currentId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        User currentUser = user(currentId, "actual@smartlogix.com", true, role("CLIENTE"));
        User otherUser = user(otherId, "otro@smartlogix.com", true, role("CLIENTE"));

        when(userRepository.findByIdWithRoles(currentId)).thenReturn(Optional.of(currentUser));
        when(userRepository.findByEmail("otro@smartlogix.com")).thenReturn(Optional.of(otherUser));

        assertThatThrownBy(() -> userService.update(
                currentId,
                new UserUpdateRequest("otro@smartlogix.com", "Actual", "Demo")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Ya existe un usuario");
    }

    // Guia: valida update roles normalizes role names and stores resolved roles.
    @Test
    void updateRolesNormalizesRoleNamesAndStoresResolvedRoles() {
        UUID userId = UUID.randomUUID();
        Role admin = role("ADMIN");
        Role pedidos = role("OPERADOR_PEDIDOS");
        User user = user(userId, "admin@smartlogix.com", true, role("CLIENTE"));

        when(userRepository.findByIdWithRoles(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findByNameIn(anyCollection())).thenReturn(List.of(admin, pedidos));
        when(userRepository.save(user)).thenReturn(user);

        UserResponse response = userService.updateRoles(
                userId,
                new RoleUpdateRequest(Set.of(" admin ", "operador_pedidos")));

        assertThat(response.roles()).containsExactly("ADMIN", "OPERADOR_PEDIDOS");
        assertThat(user.getRoles()).containsExactlyInAnyOrder(admin, pedidos);
    }

    // Guia: valida update roles rejects unknown role.
    @Test
    void updateRolesRejectsUnknownRole() {
        UUID userId = UUID.randomUUID();
        User user = user(userId, "admin@smartlogix.com", true, role("CLIENTE"));

        when(userRepository.findByIdWithRoles(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findByNameIn(anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> userService.updateRoles(
                userId,
                new RoleUpdateRequest(Set.of("NO_EXISTE"))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Roles no encontrados");
    }

    // Guia: valida update status disables user without deleting it.
    @Test
    void updateStatusDisablesUserWithoutDeletingIt() {
        UUID userId = UUID.randomUUID();
        User user = user(userId, "cliente@smartlogix.com", true, role("CLIENTE"));

        when(userRepository.findByIdWithRoles(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        UserResponse response = userService.updateStatus(userId, new UserStatusUpdateRequest(false));

        assertThat(response.enabled()).isFalse();
        verify(userRepository).save(user);
    }

    // Guia: valida find entity by id with roles rejects missing user.
    @Test
    void findEntityByIdWithRolesRejectsMissingUser() {
        UUID userId = UUID.randomUUID();

        when(userRepository.findByIdWithRoles(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findEntityByIdWithRoles(userId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Usuario no encontrado");
    }

    private User user(UUID id, String email, boolean enabled, Role role) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setPasswordHash("hash");
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
