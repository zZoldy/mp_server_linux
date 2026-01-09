/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.mirrorpage.api;

import com.app.mirrorpage.api.dto.LogDto;
import com.app.mirrorpage.server.service.ActiveUserManager;
import com.app.mirrorpage.server.service.ServerLog;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final ServerLog serverLog;
    private final ActiveUserManager activeUserManager;

    public LogController(ServerLog serverLog, ActiveUserManager activeUserManager) {
        this.serverLog = serverLog;
        this.activeUserManager = activeUserManager;
    }

    @GetMapping("/history")
    public List<LogDto> getHistorsy(@RequestParam(defaultValue = "1000") int limit) {
        return serverLog.getHistory(limit);
    }
}

