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
    public ResponseEntity<String> startIngestion(@RequestParam String filePath) {
        ingestionService.startIngestion(filePath);
        return ResponseEntity.ok("Ingestion started successfully for file: " + filePath);
    }

    @PostMapping("/bulk")
    public ResponseEntity<String> bulkLoad(@RequestParam String filePath) {
        ingestionService.bulkLoad(filePath);
        return ResponseEntity.ok("Bulk ingestion completed successfully for file: " + filePath);
    }

    @PostMapping("/stop")
    public ResponseEntity<String> stopIngestion() {
        ingestionService.stopIngestion();
        return ResponseEntity.ok("Ingestion stopped.");
    }
}
