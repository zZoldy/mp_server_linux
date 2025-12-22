/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.mirrorpage.server.web;

import com.app.mirrorpage.server.service.ServerLog;
import java.nio.file.AccessDeniedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ServerLog serverLog; // Injetamos o log aqui

    public GlobalExceptionHandler(ServerLog serverLog) {
        this.serverLog = serverLog;
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        return ResponseEntity
                .status(status)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "timestamp", Instant.now().toString(),
                        "status", status.value(),
                        "error", status.getReasonPhrase(),
                        "message", message
                ));
    }

    // Erros de validação @Valid
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<Map<String, Object>> handleValidation(Exception ex) {
        String msg = "Requisição inválida";
        if (ex instanceof MethodArgumentNotValidException manv && !manv.getBindingResult().getAllErrors().isEmpty()) {
            msg = manv.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        }
        return body(HttpStatus.BAD_REQUEST, msg);
    }

    // Erros de negócio (ex.: usuário já existe, role inexistente)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // Violação de UNIQUE/FOREIGN KEY -> 409 Conflict
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrity(DataIntegrityViolationException ex) {
        serverLog.error("GlobalExceptionHandler", "Violação de integridade: " + ex.getMessage(), ex);
        return body(HttpStatus.CONFLICT, "Violação de integridade: " + ex.getMostSpecificCause().getMessage());
    }

    // Fallback
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        serverLog.error("GlobalExceptionHandler", "Erro Interno Não Tratado: " + ex.getMessage(), ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno: " + ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        serverLog.error("GlobalExceptionHandler", "Acesso negado: Você não tem permissão para realizar esta ação." + ex.getMessage(), ex);
        return body(HttpStatus.FORBIDDEN, "Acesso negado: Você não tem permissão para realizar esta ação.");
    }
}
