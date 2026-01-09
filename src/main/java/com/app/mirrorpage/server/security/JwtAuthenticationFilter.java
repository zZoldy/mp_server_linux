/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.mirrorpage.server.security;

import com.app.mirrorpage.server.domain.user.User;
import com.app.mirrorpage.server.repo.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/ping"
    );

    private final JwtService jwt;
    private final UserRepository userRepository; // 1. Injetamos o Repositório

    // 2. Atualizamos o construtor
    public JwtAuthenticationFilter(JwtService jwt, UserRepository userRepository) {
        this.jwt = jwt;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        final String path = request.getRequestURI();

        if (isCorsPreflight(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isPublicPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);

        try {
            if (jwt.isValid(token)) {
                Jws<Claims> claims = jwt.parse(token);
                String username = claims.getBody().getSubject();
                List<String> roles = jwt.getRoles(token);

                // 3. BUSCAMOS O UTILIZADOR REAL NA BASE DE DADOS
                // Se o utilizador não existir (foi apagado?), não autenticamos
                User userEntity = userRepository.findByUsername(username).orElse(null);

                if (userEntity != null) {
                    var authorities = (roles == null ? List.<SimpleGrantedAuthority>of()
                            : roles.stream()
                                    .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                                    .map(SimpleGrantedAuthority::new)
                                    .toList());

                    // 4. Passamos o objeto 'userEntity' como Principal, não apenas a String
                    var auth = new UsernamePasswordAuthenticationToken(userEntity, null, authorities);
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    org.springframework.security.core.context.SecurityContextHolder
                            .getContext().setAuthentication(auth);
                } else {
                    // Token válido, mas user não existe no banco
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                }
            } else {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        } catch (JwtException | IllegalArgumentException ex) {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private boolean isPublicPath(String path) {
        if (PUBLIC_PATHS.contains(path)) return true;
        if (path.startsWith("/actuator")) return true;
        if ("/error".equals(path)) return true;
        return false;
    }

    private boolean isCorsPreflight(HttpServletRequest req) {
        return "OPTIONS".equalsIgnoreCase(req.getMethod())
                && req.getHeader("Origin") != null
                && req.getHeader("Access-Control-Request-Method") != null;
    }
}