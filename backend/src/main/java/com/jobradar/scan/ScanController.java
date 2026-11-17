package com.jobradar.scan;

import com.jobradar.scan.ScanService.ScanProgress;
import com.jobradar.scan.ScanService.ScanRequest;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/scans")
public class ScanController {
    private final ScanService service;
    private final ScheduledExecutorService sseTicker = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "scan-sse");
        thread.setDaemon(true);
        return thread;
    });

    public ScanController(ScanService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ScanProgress start(@AuthenticationPrincipal Long userId, @RequestBody ScanRequest request) {
        return service.start(userId, request, "MANUAL");
    }

    @GetMapping("/latest")
    public ResponseEntity<ScanProgress> latest(@AuthenticationPrincipal Long userId) {
        return service.latest(userId).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    public ScanProgress progress(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return service.progress(userId, id);
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        service.progress(userId, id); // 404 before opening the stream
        SseEmitter emitter = new SseEmitter(TimeUnit.HOURS.toMillis(13));
        AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();
        Runnable stop = () -> {
            ScheduledFuture<?> future = task.get();
            if (future != null) {
                future.cancel(false);
            }
        };
        emitter.onCompletion(stop);
        emitter.onTimeout(stop);
        emitter.onError(e -> stop.run());
        task.set(sseTicker.scheduleWithFixedDelay(() -> {
            try {
                ScanProgress progress = service.progress(userId, id);
                emitter.send(SseEmitter.event().name("progress").data(progress));
                if (!ScanRun.RUNNING.equals(progress.state())) {
                    emitter.complete();
                    stop.run();
                }
            } catch (IOException | RuntimeException e) {
                stop.run();
                emitter.completeWithError(e);
            }
        }, 0, 1, TimeUnit.SECONDS));
        return emitter;
    }
}
