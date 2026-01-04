package com.app.mirrorpage.api;

import com.app.mirrorpage.server.domain.user.Role;
import com.app.mirrorpage.server.domain.user.User;
import com.app.mirrorpage.server.repo.UserRepository;
import com.app.mirrorpage.server.security.JwtService;
import com.app.mirrorpage.server.service.ActiveUserManager;
import com.app.mirrorpage.server.service.ServerLog;
import com.app.mirrorpage.server.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {

    }

    public record RegisterRequest(@NotBlank String username, @NotBlank String password, List<String> roles) {

    }

    public record AuthResponse(String accessToken, String refreshToken, List<String> roles) {

    }

    public record RefreshRequest(@NotBlank String refreshToken) {

    }

    public record RefreshResponse(String accessToken) {

    }

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final ActiveUserManager activeUserManager;
    private final UserService userService;
    private final ServerLog serverLog;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt, ActiveUserManager activeUserManager, UserService userService, ServerLog serverLog) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.activeUserManager = activeUserManager;
        this.userService = userService;
        this.serverLog = serverLog;
    }

    @PostMapping("/login")
    // @ResponseStatus(HttpStatus.OK) <--- REMOVA (O ResponseEntity já controla o status)

    // 1. MUDANÇA: Retorno deve ser ResponseEntity<?> para aceitar Erro ou Sucesso
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {

        // --- Lógica de Bloqueio (409 CONFLICT) ---
        if (activeUserManager.isUserConnected(req.username())) {

            // 2. LOG SEM SUJEIRA: 
            // Usamos WARN para não gerar stack trace no servidor, mas aparecer no cliente.
            // Não precisa criar 'new ResponseStatusException' se não vai dar throw.
            serverLog.warn("AuthController", "Login bloqueado: " + req.username() + " já possui sessão ativa.");

            // 3. RETORNO CONTROLADO:
            // Retorna um JSON simples com a mensagem de erro
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(java.util.Map.of("message", "Este usuário já está conectado em outra sessão."));
        }
        // ----------------------------------------

        // Lógica de Autenticação
        User u = users.findByUsername(req.username())
                .orElseThrow(() -> new IllegalArgumentException("Usuário ou senha inválidos"));

        if (!u.isEnabled() || !encoder.matches(req.password(), u.getPassword())) {
            throw new IllegalArgumentException("Usuário ou senha inválidos");
        }

        // Geração dos Tokens
        String access = jwt.generateAccessToken(u);
        String refresh = jwt.generateRefreshToken(u);
        List<String> roles = u.getRoles().stream().map(Role::getName).toList();

        // 4. CRIAÇÃO DO DTO (Com o username novo)
        AuthResponse response = new AuthResponse(
                access,
                refresh,
                roles
        );

        // 5. ENCAPSULA O SUCESSO
        return ResponseEntity.ok(response);
    }

    // ... (Método refresh permanece igual) ...
    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.OK)
    public RefreshResponse refresh(@RequestBody RefreshRequest req) {
        if (req == null || req.refreshToken() == null || req.refreshToken().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "refreshToken obrigatório");
        }
        if (!jwt.isValid(req.refreshToken()) || !jwt.isRefreshToken(req.refreshToken())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token inválido");
        }

        String username = jwt.getUsername(req.refreshToken());
        User u = users.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário não encontrado"));

        String newAccess = jwt.generateAccessToken(u);
        return new RefreshResponse(newAccess);
    }

    @PostMapping("/refresh-session")
    public ResponseEntity<?> refreshSession(@RequestHeader("Authorization") String token) {
        // Remove o prefixo "Bearer " se existir
        String pureToken = token.startsWith("Bearer ") ? token.substring(7) : token;

        // Valida se o token ainda é íntegro
        if (jwt.isValid(pureToken)) {
            String username = jwt.getUsername(pureToken);

            // Tenta encontrar a sessão ativa para este usuário
            // Nota: Você pode precisar ajustar o ActiveUserManager para buscar por Username se o SessionId não estiver disponível aqui
            boolean renovado = activeUserManager.renewSessionByUsername(username);

            if (renovado) {
                serverLog.info("AuthController", "Sessão renovada via reconexão para: " + username);
                return ResponseEntity.ok(java.util.Map.of("message", "Sessão renovada com sucesso!"));
            }
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Não foi possível renovar a sessão.");
    }

    // Novo endpoint para cadastrar usuários
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        try {
            // Aqui chamamos o UserService (você precisará injetá-lo no construtor)
            userService.createUser(req.username(), req.password(), req.roles());

            // Nota: Como o AuthController usa o UserRepository diretamente, 
            // você deve injetar o UserService para aproveitar a lógica de Hash e Roles que você já criou.
            serverLog.info("AuthController", "Novo usuário criado: " + req.username());
            return ResponseEntity.status(HttpStatus.CREATED).body(java.util.Map.of("message", "Usuário criado com sucesso!"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }
}
