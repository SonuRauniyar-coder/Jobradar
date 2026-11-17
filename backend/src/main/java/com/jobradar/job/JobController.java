package com.jobradar.job;

import com.jobradar.common.ApiException;
import com.jobradar.job.JobDtos.CategoryUpdate;
import com.jobradar.job.JobDtos.DashboardStats;
import com.jobradar.job.JobDtos.JobDetail;
import com.jobradar.job.JobDtos.JobFilter;
import com.jobradar.job.JobDtos.JobRow;
import com.jobradar.job.JobDtos.PageDto;
import com.jobradar.job.JobDtos.StatusUpdate;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JobController {
    private final JobService service;
    private final JobExporter exporter;

    public JobController(JobService service, JobExporter exporter) {
        this.service = service;
        this.exporter = exporter;
    }

    @GetMapping("/api/jobs")
    public PageDto<JobRow> search(@AuthenticationPrincipal Long userId, @ModelAttribute JobFilter filter,
                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
                                  @RequestParam(required = false) String sort) {
        return service.search(userId, filter, page, size, sort);
    }

    @GetMapping("/api/jobs/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal Long userId, @ModelAttribute JobFilter filter,
                                         @RequestParam(required = false) String sort,
                                         @RequestParam(defaultValue = "csv") String format) {
        boolean xlsx = "xlsx".equalsIgnoreCase(format);
        if (!xlsx && !"csv".equalsIgnoreCase(format)) {
            throw ApiException.badRequest("format must be csv or xlsx");
        }
        JobDtos.ExportRows export = service.searchForExport(userId, filter, sort);
        List<JobRow> rows = export.rows();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"jobradar-jobs." + (xlsx ? "xlsx" : "csv") + "\"")
                // Lets the page tell the user when the export stopped at the row limit.
                .header("X-Total-Count", String.valueOf(export.totalMatching()))
                .header("X-Export-Limit", String.valueOf(JobService.EXPORT_LIMIT))
                .contentType(xlsx
                        ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        : MediaType.parseMediaType("text/csv"))
                .body(xlsx ? exporter.toXlsx(rows) : exporter.toCsv(rows));
    }

    @GetMapping("/api/jobs/{id}")
    public JobDetail detail(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return service.detail(userId, id);
    }

    @PatchMapping("/api/jobs/{id}/category")
    public JobDetail category(@AuthenticationPrincipal Long userId, @PathVariable Long id, @Valid @RequestBody CategoryUpdate body) {
        return service.setCategory(userId, id, body.category());
    }

    @PatchMapping("/api/jobs/{id}/status")
    public JobDetail status(@AuthenticationPrincipal Long userId, @PathVariable Long id, @Valid @RequestBody StatusUpdate body) {
        return service.updateStatus(userId, id, body);
    }

    @GetMapping("/api/stats/dashboard")
    public DashboardStats stats(@AuthenticationPrincipal Long userId) {
        return service.stats(userId);
    }
}
