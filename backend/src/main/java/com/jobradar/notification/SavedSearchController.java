package com.jobradar.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.common.ApiException;
import com.jobradar.job.JobDtos.JobFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/saved-searches")
public class SavedSearchController {
    public record SavedSearchRequest(@NotBlank @Size(max = 255) String name, @NotNull JobFilter filters, boolean emailAlert) {}

    public record SavedSearchDto(Long id, String name, JobFilter filters, boolean emailAlert) {}

    private final SavedSearchRepository repository;
    private final ObjectMapper mapper;

    public SavedSearchController(SavedSearchRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @GetMapping
    public List<SavedSearchDto> list(@AuthenticationPrincipal Long userId) {
        return repository.findByUserIdOrderByName(userId).stream().map(this::toDto).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedSearchDto create(@AuthenticationPrincipal Long userId, @Valid @RequestBody SavedSearchRequest request) {
        SavedSearch search = new SavedSearch();
        search.setUserId(userId);
        apply(search, request);
        return toDto(repository.save(search));
    }

    @PutMapping("/{id}")
    @Transactional
    public SavedSearchDto update(@AuthenticationPrincipal Long userId, @PathVariable Long id, @Valid @RequestBody SavedSearchRequest request) {
        SavedSearch search = require(userId, id);
        apply(search, request);
        return toDto(repository.save(search));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        repository.delete(require(userId, id));
    }

    private SavedSearch require(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Saved search"));
    }

    private void apply(SavedSearch search, SavedSearchRequest request) {
        try {
            String json = mapper.writeValueAsString(request.filters());
            if (json.length() > 4000) {
                throw ApiException.badRequest("Filters are too long");
            }
            search.setName(request.name().trim());
            search.setFiltersJson(json);
            search.setEmailAlert(request.emailAlert());
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("Could not store these filters");
        }
    }

    private SavedSearchDto toDto(SavedSearch search) {
        try {
            return new SavedSearchDto(search.getId(), search.getName(), mapper.readValue(search.getFiltersJson(), JobFilter.class), search.isEmailAlert());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored filters are corrupt for search " + search.getId(), e);
        }
    }
}
