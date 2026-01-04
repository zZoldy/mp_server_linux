/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.mirrorpage.server.security;

import com.app.mirrorpage.server.domain.user.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class JwtService {

    private final Key key;
    private final int accessMinutes;
    private final int refreshDays;

    public JwtService(
            @Value("${mirrorpage.jwt.secret}") String secret,
            @Value("${mirrorpage.jwt.access-minutes:60}") int accessMinutes,
            @Value("${mirrorpage.jwt.refresh-days:7}") int refreshDays
    ) {
        // aceita segredo como texto ASCII longo ou Base64
        byte[] bytes = secret.length() >= 32 ? secret.getBytes() : Decoders.BASE64.decode(secret);
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessMinutes = accessMinutes;
        this.refreshDays = refreshDays;
    }

    public String generateAccessToken(User user) {
        return buildToken(user, "access", Instant.now().plus(accessMinutes, ChronoUnit.MINUTES));
    }

    public String generateRefreshToken(User user) {
        return buildToken(user, "refresh", Instant.now().plus(refreshDays, ChronoUnit.DAYS));
    }

    private String buildToken(User user, String typ, Instant expiresAt) {
        List<String> roles = user.getRoles().stream().map(r -> r.getName()).collect(Collectors.toList());
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(user.getUsername())
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(expiresAt))
                .addClaims(Map.of(
                        "roles", roles,
                        "typ", typ
                ))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public Jws<Claims> parse(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
    }

    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String getUsername(String token) {
        return parse(token).getBody().getSubject();
    }

    @SuppressWarnings("unchecked")
    public List<String> getRoles(String token) {
        Object val = parse(token).getBody().get("roles");
        if (val instanceof List) {
            return (List<String>) val;
        }
        return List.of();
    }

    public boolean isRefreshToken(String token) {
        Object typ = parse(token).getBody().get("typ");
        return "refresh".equals(typ);
    }

    public int getAccessMinutes() {
        return accessMinutes;
    }

}
