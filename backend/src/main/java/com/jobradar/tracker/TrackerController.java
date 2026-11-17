package com.jobradar.tracker;

import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tracker")
public class TrackerController {
    private final TrackerService service;

    public TrackerController(TrackerService service) {
        this.service = service;
    }

    @GetMapping("/board")
    public List<TrackerService.Card> board(@AuthenticationPrincipal Long userId) {
        return service.board(userId);
    }

    @GetMapping("/stats")
    public TrackerService.TrackerStats stats(@AuthenticationPrincipal Long userId) {
        return service.stats(userId);
    }
}
