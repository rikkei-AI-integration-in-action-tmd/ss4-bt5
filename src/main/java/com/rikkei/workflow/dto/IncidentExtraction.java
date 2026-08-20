package com.rikkei.workflow.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record IncidentExtraction(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Unique order tracking code, e.g., ORD-7788")
        String orderCode,

        @JsonProperty(required = true)
        @JsonPropertyDescription("Vehicle license plate, e.g., 29C-998.77")
        String licensePlate,

        @JsonPropertyDescription("Urgency level: LOW, MEDIUM, HIGH, CRITICAL")
        String urgency,

        @JsonPropertyDescription("Detailed description of the incident")
        String description,

        @JsonPropertyDescription("Reported incident timestamp")
        String incidentTime
) {}
