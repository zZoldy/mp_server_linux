/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.mirrorpage.server.config;

import com.app.mirrorpage.server.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable());
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login").permitAll() // login sem token
                .requestMatchers("/api/ping").permitAll()
                .requestMatchers("/api/auth/refresh").permitAll()
                .requestMatchers("/ws/**").authenticated()
                .requestMatchers("/api/admin/**").hasRole("SUPORTE") // admin exige role
                .requestMatchers("/api/tree/**").authenticated() // precisa estar aqui
                .requestMatchers("/api/me/**").authenticated()
                .requestMatchers("/api/sheet/**").authenticated()
                .requestMatchers("/api/logs/**").authenticated()
                .anyRequest().authenticated() // demais precisam de token
        );

        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        http.formLogin(login -> login.disable());

        return http.build();
    }
}
