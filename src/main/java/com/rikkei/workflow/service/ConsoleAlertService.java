package com.rikkei.workflow.service;

import com.rikkei.workflow.entity.IncidentReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ConsoleAlertService {

    private static final Logger log = LoggerFactory.getLogger(ConsoleAlertService.class);

    public void triggerEmergencyAlert(IncidentReport report, boolean simulateFailure) {
        if (simulateFailure) {
            log.error("CRITICAL: Alert transmission subsystem failure! Hardware device or gateway is unavailable.");
            throw new RuntimeException("Simulated alert system hardware failure / Timeout exception");
        }

        String alertBanner = String.format("""
                
                ========================================================================================
                [EMERGENCY RED ALERT] - LOGISTICS DISPATCH CENTER
                ----------------------------------------------------------------------------------------
                INCIDENT ID      : #%d
                ORDER CODE       : %s
                LICENSE PLATE    : %s
                URGENCY LEVEL    : %s
                INCIDENT TIME    : %s
                DESCRIPTION      : %s
                ACTION REQUIRED  : IMMEDIATE DISPATCH OF EMERGENCY RESPONSE UNIT
                ========================================================================================
                """,
                report.getId(),
                report.getOrderCode(),
                report.getLicensePlate(),
                report.getUrgency(),
                report.getIncidentTime(),
                report.getDescription()
        );

        log.warn("{}", alertBanner);
    }
}
