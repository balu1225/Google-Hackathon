package com.fraudshield.backend.controller;

import com.fraudshield.backend.service.IngestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ingest")
@CrossOrigin(origins = "*")
public class IngestionController {

    @Autowired
    private IngestionService ingestionService;

    @PostMapping("/start")
    public ResponseEntity<String> startIngestion(@RequestParam(required = false) String filePath) {
        ingestionService.startIngestion(filePath);
        return ResponseEntity.ok("Ingestion started successfully.");
    }

    @PostMapping("/stop")
    public ResponseEntity<String> stopIngestion() {
        ingestionService.stopIngestion();
        return ResponseEntity.ok("Ingestion stopped.");
    }
}
