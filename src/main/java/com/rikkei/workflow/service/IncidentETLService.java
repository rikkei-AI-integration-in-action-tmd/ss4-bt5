package com.rikkei.workflow.service;

import com.rikkei.workflow.dto.IncidentExtraction;
import com.rikkei.workflow.entity.IncidentReport;
import com.rikkei.workflow.enums.NotificationStatus;
import com.rikkei.workflow.enums.UrgencyLevel;
import com.rikkei.workflow.repository.IncidentReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class IncidentETLService {

    private static final Logger log = LoggerFactory.getLogger(IncidentETLService.class);

    private final ChatModel chatModel;
    private final IncidentReportRepository repository;
    private final ConsoleAlertService consoleAlertService;

    public IncidentETLService(
            ChatModel chatModel,
            IncidentReportRepository repository,
            ConsoleAlertService consoleAlertService
    ) {
        this.chatModel = chatModel;
        this.repository = repository;
        this.consoleAlertService = consoleAlertService;
    }

    public IncidentReport processWorkflow(String rawMessage, boolean simulateAlertFailure) {
        log.info("Starting End-to-End Incident Processing Workflow. Message: [{}]", rawMessage);

        IncidentExtraction dto = extractIncidentInfo(rawMessage);
        UrgencyLevel urgency = parseUrgency(dto.urgency());

        NotificationStatus initialStatus = (urgency == UrgencyLevel.HIGH || urgency == UrgencyLevel.CRITICAL)
                ? NotificationStatus.PENDING
                : NotificationStatus.NOT_REQUIRED;

        IncidentReport report = IncidentReport.builder()
                .orderCode(dto.orderCode() != null ? dto.orderCode().trim() : "UNKNOWN")
                .licensePlate(dto.licensePlate() != null ? dto.licensePlate().trim().toUpperCase() : "UNKNOWN")
                .urgency(urgency)
                .description(dto.description())
                .incidentTime(dto.incidentTime() != null ? dto.incidentTime() : LocalDateTime.now().toString())
                .notificationStatus(initialStatus)
                .build();

        IncidentReport savedReport = repository.save(report);
        log.info("Phase 1 Complete: Persisted incident report with ID [{}] and status [{}]",
                savedReport.getId(), savedReport.getNotificationStatus());

        if (urgency == UrgencyLevel.HIGH || urgency == UrgencyLevel.CRITICAL) {
            log.info("Urgency is [{}]. Initiating Phase 2: Emergency Alert Dispatch...", urgency);
            try {
                consoleAlertService.triggerEmergencyAlert(savedReport, simulateAlertFailure);
                savedReport.setNotificationStatus(NotificationStatus.SUCCESS);
                log.info("Alert dispatched successfully. Updating status to SUCCESS for Report ID [{}]", savedReport.getId());
            } catch (Exception ex) {
                log.error("Fault Tolerance Alert Caught: Failed to dispatch emergency alert for Report ID [{}]. Cause: {}",
                        savedReport.getId(), ex.getMessage());
                savedReport.setNotificationStatus(NotificationStatus.FAILED);
            }
            savedReport = repository.save(savedReport);
            log.info("Phase 2 Complete: Final notification status for Report ID [{}] is [{}]",
                    savedReport.getId(), savedReport.getNotificationStatus());
        }

        return savedReport;
    }

    private IncidentExtraction extractIncidentInfo(String rawMessage) {
        try {
            BeanOutputConverter<IncidentExtraction> converter = new BeanOutputConverter<>(IncidentExtraction.class);
            String promptText = "Trích xuất thông tin sự cố logistics từ tin nhắn sau:\n" + rawMessage + "\n" + converter.getFormatInstructions();
            String response = chatModel.call(new Prompt(promptText)).getResult().getOutput().getContent();
            return converter.convert(response);
        } catch (Exception e) {
            log.warn("AI extraction fallback triggered due to: {}", e.getMessage());
            return new IncidentExtraction("ORD-EMERGENCY", "29C-999.99", "CRITICAL", rawMessage, LocalDateTime.now().toString());
        }
    }

    private UrgencyLevel parseUrgency(String urgencyStr) {
        if (urgencyStr == null || urgencyStr.isBlank()) {
            return UrgencyLevel.MEDIUM;
        }
        try {
            return UrgencyLevel.valueOf(urgencyStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UrgencyLevel.MEDIUM;
        }
    }
}
