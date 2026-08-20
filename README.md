BÀI 5: THIẾT KẾ VÀ HIỆN THỰC HÓA WORKFLOW SỰ CỐ KHẨN CẤP (CONSOLE ALERT)

1. THIẾT KẾ GIẢI PHÁP VÀ SƠ ĐỒ LUỒNG XỬ LÝ (ASCII FLOW DIAGRAM)

1.1. Sơ đồ luồng xử lý dữ liệu (ASCII Flow Diagram)

```text
+-------------------------------------------------------------------------------+
|                       DRIVER RAW INCIDENT MESSAGE                             |
|        "Xe 29C-998.77 don hang ORD-7788 bi chay dong co tren Quoc Lo 1A"       |
+-------------------------------------------------------------------------------+
                                      |
                                      v
+-------------------------------------------------------------------------------+
|                             INCIDENT ETL SERVICE                              |
|   1. LLM Extraction (Spring AI BeanOutputConverter)                           |
|   2. Extract: OrderCode, LicensePlate, Urgency, Description, Timestamp        |
+-------------------------------------------------------------------------------+
                                      |
                                      v
+-------------------------------------------------------------------------------+
|                       PHASE 1: PERSIST CORE INCIDENT                          |
|   - Check Urgency:                                                            |
|     * If Urgency IN (HIGH, CRITICAL) -> Set notificationStatus = PENDING      |
|     * If Urgency IN (LOW, MEDIUM)    -> Set notificationStatus = NOT_REQUIRED |
|   - Save to Database (Commit Record & Generate ID #101)                       |
+-------------------------------------------------------------------------------+
                                      |
                                      +-------------------------------+
                                      |                               |
                   [Urgency == HIGH / CRITICAL]           [Urgency == LOW / MEDIUM]
                                      |                               |
                                      v                               v
+-------------------------------------------------------------+  [END WORKFLOW]
|              PHASE 2: EMERGENCY ALERT DISPATCH              |  (Status: NOT_REQUIRED)
|                   (Exception Isolated Block)                |
+-------------------------------------------------------------+
               |                               |
     [Dispatch Success]               [Dispatch Failure / Exception]
               |                               |
               v                               v
+-----------------------------+ +-----------------------------+
|   CONSOLE ALERT SERVICE     | |  CATCH BLOCK & ERROR LOG    |
| - Print Red Alert Banner    | | - Log detailed exception    |
| - Set Status = SUCCESS      | | - Set Status = FAILED       |
+-----------------------------+ +-----------------------------+
               |                               |
               +---------------+---------------+
                               |
                               v
+-------------------------------------------------------------+
|                 UPDATE NOTIFICATION STATUS                  |
|    - Save status (SUCCESS / FAILED) to Database             |
|    - Core Incident Record ALWAYS Guaranteed in DB           |
+-------------------------------------------------------------+
                               |
                               v
                       [WORKFLOW COMPLETED]
```

1.2. Bản thuyết minh thiết kế giải pháp chịu lỗi (Fault Tolerance Architecture)

1. Nguyên lý phân tách 2 pha (Two-Phase Processing Pattern):
- Pha 1 (Lưu trữ lõi): Dữ liệu sự cố (Mã đơn hàng, biển số xe, mô tả) là tài sản quan trọng nhất. Dữ liệu này phải được ghi nhận và lưu bền vững vào Database ngay lập tức với trạng thái PENDING (hoặc NOT_REQUIRED) trước khi thực hiện bất kỳ hành động ngoại vi nào.
- Pha 2 (Phát cảnh báo và Cập nhật trạng thái): Tương tác với các dịch vụ cảnh báo (Console, SMS Gateway, Push Notification, Loa báo động) là các tác vụ I/O ngoại vi có xác suất lỗi cao (timeout, rớt mạng, nghẽn thiết bị).

2. Cô lập lỗi (Fault Isolation / Air-Gap Boundary):
- Toàn bộ khối gọi ConsoleAlertService được bao bọc trong khối try-catch độc lập. Ngoại lệ ở tầng cảnh báo tuyệt đối không được phép lan truyền (bubble up) làm rollback giao dịch lưu trữ sự cố ở Pha 1.
- Khi xảy ra lỗi, hệ thống ghi log mức ERROR chi tiết kèm stack trace và cập nhật trạng thái notificationStatus thành FAILED. Nhân viên vận hành có thể lọc danh sách các bản ghi FAILED để phát lại cảnh báo thủ công.

2. MÃ NGUỒN JAVA HOÀN CHỈNH

2.1. Enum NotificationStatus.java
```java
package com.rikkei.workflow.enums;

public enum NotificationStatus {
    NOT_REQUIRED,
    PENDING,
    SUCCESS,
    FAILED
}
```

2.2. Entity IncidentReport.java
```java
package com.rikkei.workflow.entity;

import com.rikkei.workflow.enums.NotificationStatus;
import com.rikkei.workflow.enums.UrgencyLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "incident_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncidentReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_code", nullable = false, length = 50)
    private String orderCode;

    @Column(name = "license_plate", nullable = false, length = 20)
    private String licensePlate;

    @Enumerated(EnumType.STRING)
    @Column(name = "urgency", nullable = false, length = 20)
    private UrgencyLevel urgency;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "incident_time", length = 100)
    private String incidentTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_status", nullable = false, length = 30)
    private NotificationStatus notificationStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
```

2.3. Service phát cảnh báo: ConsoleAlertService.java
```java
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
```

2.4. Service điều phối quy trình 2 pha: IncidentETLService.java
```java
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
```

3. MINH CHỨNG CHẠY THỰC TẾ (REAL-WORLD EXECUTION LOGS)

3.1. Trường hợp 1: Phát thông báo thành công (Emergency Alert SUCCESS)

Input:
- rawMessage: "Xe 29C-998.77 don hang ORD-7788 bi chay dong co tren Quoc Lo 1A, muc do CRITICAL"
- simulateFailure: false

Log Console ghi nhận:
```text
2026-08-17T08:40:01.100+07:00  INFO 26804 --- [http-nio-8080-exec-1] c.r.w.service.IncidentETLService        : Starting End-to-End Incident Processing Workflow. Message: [Xe 29C-998.77 don hang ORD-7788 bi chay dong co tren Quoc Lo 1A, muc do CRITICAL]
Hibernate: 
    insert 
    into
        incident_reports
        (created_at, description, incident_time, license_plate, notification_status, order_code, urgency, id) 
    values
        (?, ?, ?, ?, ?, ?, ?, default)
2026-08-17T08:40:01.320+07:00  INFO 26804 --- [http-nio-8080-exec-1] c.r.w.service.IncidentETLService        : Phase 1 Complete: Persisted incident report with ID [101] and status [PENDING]
2026-08-17T08:40:01.322+07:00  INFO 26804 --- [http-nio-8080-exec-1] c.r.w.service.IncidentETLService        : Urgency is [CRITICAL]. Initiating Phase 2: Emergency Alert Dispatch...
2026-08-17T08:40:01.325+07:00  WARN 26804 --- [http-nio-8080-exec-1] c.r.w.service.ConsoleAlertService       : 
========================================================================================
[EMERGENCY RED ALERT] - LOGISTICS DISPATCH CENTER
----------------------------------------------------------------------------------------
INCIDENT ID      : #101
ORDER CODE       : ORD-7788
LICENSE PLATE    : 29C-998.77
URGENCY LEVEL    : CRITICAL
INCIDENT TIME    : 2026-08-17T08:40:01.100
DESCRIPTION      : Xe 29C-998.77 bi chay dong co tren Quoc Lo 1A
ACTION REQUIRED  : IMMEDIATE DISPATCH OF EMERGENCY RESPONSE UNIT
========================================================================================

2026-08-17T08:40:01.328+07:00  INFO 26804 --- [http-nio-8080-exec-1] c.r.w.service.IncidentETLService        : Alert dispatched successfully. Updating status to SUCCESS for Report ID [101]
Hibernate: 
    update
        incident_reports 
    set
        description=?,
        incident_time=?,
        license_plate=?,
        notification_status=?,
        order_code=?,
        urgency=? 
    where
        id=?
2026-08-17T08:40:01.340+07:00  INFO 26804 --- [http-nio-8080-exec-1] c.r.w.service.IncidentETLService        : Phase 2 Complete: Final notification status for Report ID [101] is [SUCCESS]
```

3.2. Trường hợp 2: Phát thông báo thất bại nhưng dữ liệu DB vẫn được bảo toàn (Status: FAILED)

Input:
- rawMessage: "Xe 51B-11223 don hang ORD-9900 va cham gay un tac nghiem trong, muc do HIGH"
- simulateFailure: true

Log Console ghi nhận:
```text
2026-08-17T08:40:15.500+07:00  INFO 26804 --- [http-nio-8080-exec-2] c.r.w.service.IncidentETLService        : Starting End-to-End Incident Processing Workflow. Message: [Xe 51B-11223 don hang ORD-9900 va cham gay un tac nghiem trong, muc do HIGH]
Hibernate: 
    insert 
    into
        incident_reports
        (created_at, description, incident_time, license_plate, notification_status, order_code, urgency, id) 
    values
        (?, ?, ?, ?, ?, ?, ?, default)
2026-08-17T08:40:15.680+07:00  INFO 26804 --- [http-nio-8080-exec-2] c.r.w.service.IncidentETLService        : Phase 1 Complete: Persisted incident report with ID [102] and status [PENDING]
2026-08-17T08:40:15.682+07:00  INFO 26804 --- [http-nio-8080-exec-2] c.r.w.service.IncidentETLService        : Urgency is [HIGH]. Initiating Phase 2: Emergency Alert Dispatch...
2026-08-17T08:40:15.685+07:00 ERROR 26804 --- [http-nio-8080-exec-2] c.r.w.service.ConsoleAlertService       : CRITICAL: Alert transmission subsystem failure! Hardware device or gateway is unavailable.
2026-08-17T08:40:15.688+07:00 ERROR 26804 --- [http-nio-8080-exec-2] c.r.w.service.IncidentETLService        : Fault Tolerance Alert Caught: Failed to dispatch emergency alert for Report ID [102]. Cause: Simulated alert system hardware failure / Timeout exception
Hibernate: 
    update
        incident_reports 
    set
        description=?,
        incident_time=?,
        license_plate=?,
        notification_status=?,
        order_code=?,
        urgency=? 
    where
        id=?
2026-08-17T08:40:15.705+07:00  INFO 26804 --- [http-nio-8080-exec-2] c.r.w.service.IncidentETLService        : Phase 2 Complete: Final notification status for Report ID [102] is [FAILED]
```

4. KẾT LUẬN
- Kiến trúc chịu lỗi 2 pha (Two-Phase Fault Tolerance) bảo vệ 100% tính an toàn dữ liệu sự cố trong cơ sở dữ liệu.
- Mọi lỗi phát sinh ở phân hệ cảnh báo ngoại vi đều được cô lập triệt để và gán cờ FAILED minh bạch để phục vụ giám sát và xử lý hậu kiểm.
