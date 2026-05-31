package com.micewriter.sandbox.controller;

import com.micewriter.sandbox.loadtest.LoadTestRun;
import com.micewriter.sandbox.loadtest.LoadTestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/loadtest")
public class LoadTestController {

    private final LoadTestService service;

    public LoadTestController(LoadTestService service) {
        this.service = service;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(@RequestBody StartRequest req) {
        try {
            LoadTestRun run = service.startSingle(req.rate, req.payloadSizeBytes, req.durationSec);
            return ResponseEntity.ok(Map.of(
                    "runId", run.runId(),
                    "status", run.status().name()
            ));
        } catch (IllegalStateException e) {
            LoadTestRun active = service.activeRun();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", e.getMessage(),
                    "activeRunId", active != null ? active.runId() : ""
            ));
        }
    }

    @PostMapping("/sweep")
    public ResponseEntity<Map<String, Object>> sweep(@RequestBody SweepRequest req) {
        try {
            List<int[]> cells = new ArrayList<>();
            if (req.cells != null) {
                for (SweepCell c : req.cells) {
                    cells.add(new int[]{c.rate, c.payloadSizeBytes, c.durationSec});
                }
            }
            LoadTestRun run = service.startSweep(cells, req.restSecondsBetween);
            return ResponseEntity.ok(Map.of(
                    "runId", run.runId(),
                    "status", run.status().name(),
                    "cellCount", run.cells().size()
            ));
        } catch (IllegalStateException e) {
            LoadTestRun active = service.activeRun();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", e.getMessage(),
                    "activeRunId", active != null ? active.runId() : ""
            ));
        }
    }

    @GetMapping("/{runId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String runId) {
        LoadTestRun run = service.get(runId);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(run.toStatusMap());
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        List<Map<String, Object>> runs = new ArrayList<>();
        for (LoadTestRun r : service.list()) {
            Map<String, Object> brief = new java.util.LinkedHashMap<>();
            brief.put("runId", r.runId());
            brief.put("status", r.status().name());
            brief.put("kind", r.isSweep() ? "SWEEP" : "SINGLE");
            brief.put("startedAt", r.startedAt().toString());
            brief.put("cellCount", r.cells().size());
            runs.add(brief);
        }
        return ResponseEntity.ok(Map.of("runs", runs));
    }

    @PostMapping("/{runId}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable String runId) {
        boolean stopped = service.stop(runId);
        if (!stopped) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "no active run with that id"
            ));
        }
        return ResponseEntity.ok(Map.of("status", "STOPPED"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    // -------------------------------------------------------------------------

    public static class StartRequest {
        public int rate;
        public int payloadSizeBytes;
        public int durationSec;
    }

    public static class SweepRequest {
        public List<SweepCell> cells;
        public int restSecondsBetween = 60;
    }

    public static class SweepCell {
        public int rate;
        public int payloadSizeBytes;
        public int durationSec;
    }
}
