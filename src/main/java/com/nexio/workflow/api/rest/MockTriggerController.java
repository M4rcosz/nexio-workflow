package com.nexio.workflow.api.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/mock")
public class MockTriggerController {

    // TODO Sprint 3: injetar TriggerWorkflowUseCase

    @PostMapping("/trigger")
    public ResponseEntity<Map<String, String>> trigger(@RequestBody Map<String, Object> body) {
        // placeholder -- implementar no Sprint 3
        return ResponseEntity.accepted()
                .body(Map.of("status", "accepted", "message", "not implemented yet"));
    }
}
