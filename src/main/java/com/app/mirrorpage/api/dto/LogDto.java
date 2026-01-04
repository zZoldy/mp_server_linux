/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.mirrorpage.api.dto;

public class LogDto {

    private String timestamp;
    private String level;
    private String context; // Garanta que este campo existe
    private String message;
    private String stackTrace;
    private String user;

    // Construtor Vazio (Necessário para o Jackson/Spring)
    public LogDto() {
    }

    // Construtor Cheio
    public LogDto(String timestamp, String level, String context, String message, String stackTrace, String user) {
        this.timestamp = timestamp;
        this.level = level;
        this.context = context;
        this.message = message;
        this.stackTrace = stackTrace;
        this.user = user;
    }

    // --- GETTERS E SETTERS (O Erro está na falta destes) ---
    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    } // <--- O método que faltava

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

}
