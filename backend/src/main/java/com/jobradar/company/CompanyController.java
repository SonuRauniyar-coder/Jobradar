package com.jobradar.company;

import com.jobradar.common.ApiException;
import com.jobradar.company.CompanyDtos.CompanyDto;
import com.jobradar.company.CompanyDtos.CompanyRequest;
import com.jobradar.company.CompanyDtos.ListDto;
import com.jobradar.company.CompanyDtos.ListRequest;
import com.jobradar.company.CompanyDtos.UploadResult;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class CompanyController {
    private static final String TEMPLATE = """
            company_name,career_page_url,website,location_preference
            Example Corp,https://example.com/careers,https://example.com,Bangalore
            Another Company,,https://another.example.org,
            """;

    private final CompanyService service;
    private final CompanyImportService importService;

    public CompanyController(CompanyService service, CompanyImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    @PostMapping("/api/lists")
    @ResponseStatus(HttpStatus.CREATED)
    public ListDto createList(@AuthenticationPrincipal Long userId, @Valid @RequestBody ListRequest request) {
        return service.createList(userId, request.name());
    }

    @GetMapping("/api/lists")
    public List<ListDto> lists(@AuthenticationPrincipal Long userId) {
        return service.getLists(userId);
    }

    @DeleteMapping("/api/lists/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteList(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        service.deleteList(userId, id);
    }

    /** Send commit=false to get the validation preview, then commit=true to save the valid rows. */
    @PostMapping(value = "/api/lists/{id}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResult upload(@AuthenticationPrincipal Long userId, @PathVariable Long id,
                               @RequestParam("file") MultipartFile file,
                               @RequestParam(defaultValue = "false") boolean commit) {
        try (InputStream in = file.getInputStream()) {
            return importService.importFile(userId, id, file.getOriginalFilename(), in, commit);
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read the uploaded file");
        }
    }

    @GetMapping("/api/lists/template")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"jobradar-companies-template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(TEMPLATE.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/api/companies")
    public List<CompanyDto> companies(@AuthenticationPrincipal Long userId, @RequestParam(required = false) Long listId) {
        return service.getCompanies(userId, listId);
    }

    @GetMapping("/api/companies/{id}")
    public CompanyDto company(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return service.getCompany(userId, id);
    }

    @PostMapping("/api/companies")
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyDto create(@AuthenticationPrincipal Long userId, @Valid @RequestBody CompanyRequest request) {
        return service.create(userId, request);
    }

    @PutMapping("/api/companies/{id}")
    public CompanyDto update(@AuthenticationPrincipal Long userId, @PathVariable Long id,
                             @Valid @RequestBody CompanyRequest request) {
        return service.update(userId, id, request);
    }

    @DeleteMapping("/api/companies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        service.delete(userId, id);
    }
}
