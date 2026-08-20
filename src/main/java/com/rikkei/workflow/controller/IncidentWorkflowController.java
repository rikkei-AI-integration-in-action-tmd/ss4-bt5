package com.rikkei.workflow.controller;

import com.rikkei.workflow.entity.IncidentReport;
import com.rikkei.workflow.service.IncidentETLService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/incident/workflow")
public class IncidentWorkflowController {

    private final IncidentETLService workflowService;

    public IncidentWorkflowController(IncidentETLService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping("/process")
    public ResponseEntity<IncidentReport> processIncident(
            @RequestParam String rawMessage,
            @RequestParam(defaultValue = "false") boolean simulateFailure
    ) {
        IncidentReport result = workflowService.processWorkflow(rawMessage, simulateFailure);
        return ResponseEntity.ok(result);
    }
}
