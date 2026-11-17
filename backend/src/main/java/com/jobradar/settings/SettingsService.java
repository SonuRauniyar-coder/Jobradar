package com.jobradar.settings;

import com.jobradar.common.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {
    private static final int MAX_STORED = 4000;

    private final UserSettingsRepository repository;

    public SettingsService(UserSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public UserSettings get(Long userId) {
        return repository.findById(userId).orElseGet(() -> {
            UserSettings defaults = new UserSettings();
            defaults.setUserId(userId);
            return defaults;
        });
    }

    public List<String> profileKeywords(Long userId) {
        return SettingsController.split(get(userId).getProfileKeywords());
    }

    @Transactional
    public UserSettings update(Long userId, SettingsController.SettingsDto dto) {
        UserSettings settings = get(userId);
        settings.setProfileKeywords(join(dto.profileKeywords()));
        settings.setPreferredLocations(join(dto.preferredLocations()));
        settings.setScanConcurrency(dto.scanConcurrency());
        settings.setRateLimitPerSec(dto.rateLimitPerSec());
        return repository.save(settings);
    }

    private static String join(List<String> values) {
        String joined = String.join(",", values.stream()
                .filter(java.util.Objects::nonNull)
                .map(v -> v.replace(",", " ").trim())
                .filter(v -> !v.isEmpty())
                .toList());
        if (joined.length() > MAX_STORED) {
            throw ApiException.badRequest("Too many entries");
        }
        return joined;
    }
}
