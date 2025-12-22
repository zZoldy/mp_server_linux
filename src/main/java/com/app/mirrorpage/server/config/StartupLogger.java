/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.mirrorpage.server.config;

import com.app.mirrorpage.server.service.ServerLog;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
// Importe a SUA classe de log aqui
// import com.mirrorpage.service.SeuLogService; 

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class StartupLogger {

    // 1. Injete a sua classe de controle de log (se for um Service/Component)
    // @Autowired
    // private SeuLogService logService;
    
    @Autowired
    private ServerLog serverLog;
    
    @EventListener(ApplicationReadyEvent.class)
    public void logarInicioDoServidor() {
        // Gera a data formatada
        String dataHora = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS"));
        
        String mensagem = "Servidor iniciado em: " + dataHora;
        
        serverLog.info("SYSTEM", mensagem);
        
    }
}
