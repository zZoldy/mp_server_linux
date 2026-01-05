/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.mirrorpage.server.web.admin;

import com.app.mirrorpage.server.domain.user.Role;
import com.app.mirrorpage.server.domain.user.User;
import com.app.mirrorpage.server.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@RestController
@RequestMapping("/api/admin/users")
@EnableMethodSecurity
public class AdminUserController {

    private final UserService users;

    public AdminUserController(UserService users) {
        this.users = users;
    }

    public record CreateUserRequest(
            @NotBlank
            @Size(min = 3, max = 64) String username,
            @NotBlank
            @Size(min = 6, max = 72) String password,
            List<String> roles
            ) {

    }

    public record UserResponse(Long id, String username, List<String> roles) {

    }

    @PostMapping
    // ⚠️ Temporariamente aberto; após JWT colocaremos @PreAuthorize("hasRole('SUPORTE')")
    public UserResponse create(@Valid @RequestBody CreateUserRequest req) {
        User u = users.createUser(
                req.username(),
                req.password(),
                req.roles() == null ? List.of() : req.roles()
        );
        List<String> roleNames = u.getRoles().stream().map(Role::getName).toList();
        return new UserResponse(u.getId(), u.getUsername(), roleNames);
    }
}
