package com.jobradar.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    public record SettingsDto(
            @NotNull List<@Size(max = 100) String> profileKeywords,
            @NotNull List<@Size(max = 100) String> preferredLocations,
            @Min(1) @Max(50) int scanConcurrency,
            @DecimalMin("0.1") @DecimalMax("5.0") double rateLimitPerSec) {}

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping
    public SettingsDto get(@AuthenticationPrincipal Long userId) {
        return toDto(service.get(userId));
    }

    @PutMapping
    public SettingsDto update(@AuthenticationPrincipal Long userId, @Valid @RequestBody SettingsDto dto) {
        return toDto(service.update(userId, dto));
    }

    private static SettingsDto toDto(UserSettings s) {
        return new SettingsDto(split(s.getProfileKeywords()), split(s.getPreferredLocations()),
                s.getScanConcurrency(), s.getRateLimitPerSec());
    }

    static List<String> split(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }
}
