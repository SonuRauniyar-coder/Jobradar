package com.jobradar.company;

import com.jobradar.common.ApiException;
import com.jobradar.common.UrlUtil;
import com.jobradar.company.CompanyDtos.RowStatus;
import com.jobradar.company.CompanyDtos.UploadResult;
import com.jobradar.company.CompanyDtos.UploadRow;
import com.jobradar.company.CompanyFileParser.ParsedRow;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyImportService {
    public static final int MAX_COMPANIES_PER_LIST = 500;

    private final CompanyFileParser parser;
    private final CompanyListRepository lists;
    private final CompanyRepository companies;

    public CompanyImportService(CompanyFileParser parser, CompanyListRepository lists, CompanyRepository companies) {
        this.parser = parser;
        this.lists = lists;
        this.companies = companies;
    }

    /** Validates the file; only writes to the database when commit is true. */
    @Transactional
    public UploadResult importFile(Long userId, Long listId, String filename, InputStream in, boolean commit) {
        // A commit locks the list so parallel commits of the same file can't each add the same companies.
        CompanyList list = (commit ? lists.findForUpdate(listId, userId) : lists.findByIdAndUserId(listId, userId))
                .orElseThrow(() -> ApiException.notFound("List"));
        List<ParsedRow> parsed = parser.parse(filename, in);

        List<Company> existing = companies.findByListId(listId);
        Set<String> names = new HashSet<>();
        Set<String> domains = new HashSet<>();
        for (Company c : existing) {
            names.add(key(c.getName()));
            addIfPresent(domains, UrlUtil.domain(c.getWebsite()));
        }

        int capacity = MAX_COMPANIES_PER_LIST - existing.size();
        List<UploadRow> rows = new ArrayList<>();
        List<Company> toSave = new ArrayList<>();
        int valid = 0, errors = 0, duplicates = 0;

        for (ParsedRow row : parsed) {
            String careerUrl = null, website = null;
            String error = null;
            if (row.name() == null) {
                error = "company_name is required";
            } else if (row.name().length() > 255) {
                error = "company_name is longer than 255 characters";
            } else if (row.locationPreference() != null && row.locationPreference().length() > 255) {
                error = "location_preference is longer than 255 characters";
            }
            if (error == null) {
                try {
                    careerUrl = UrlUtil.normalize(row.careerUrl());
                } catch (IllegalArgumentException e) {
                    error = urlError("career_page_url", e);
                }
            }
            if (error == null) {
                try {
                    website = UrlUtil.normalize(row.website());
                } catch (IllegalArgumentException e) {
                    error = urlError("website", e);
                }
            }
            if (error != null) {
                errors++;
                rows.add(toRow(row, RowStatus.ERROR, error));
                continue;
            }

            String domain = UrlUtil.domain(website);
            if (names.contains(key(row.name()))) {
                duplicates++;
                rows.add(toRow(row, RowStatus.DUPLICATE, "A company with this name is already in the list"));
                continue;
            }
            if (domain != null && domains.contains(domain)) {
                duplicates++;
                rows.add(toRow(row, RowStatus.DUPLICATE, "A company with this domain is already in the list"));
                continue;
            }
            if (valid >= capacity) {
                errors++;
                rows.add(toRow(row, RowStatus.ERROR, "List limit of " + MAX_COMPANIES_PER_LIST + " companies reached"));
                continue;
            }

            names.add(key(row.name()));
            addIfPresent(domains, domain);
            valid++;
            rows.add(toRow(row, RowStatus.VALID, null));

            Company company = new Company();
            company.setList(list);
            company.setName(row.name());
            company.setWebsite(website);
            company.setCareerUrl(careerUrl);
            company.setLocationPreference(row.locationPreference());
            toSave.add(company);
        }

        if (commit) {
            companies.saveAll(toSave);
        }
        return new UploadResult(rows, valid, errors, duplicates, commit ? toSave.size() : 0, commit);
    }

    private static UploadRow toRow(ParsedRow r, RowStatus status, String message) {
        return new UploadRow(r.rowNumber(), r.name(), r.careerUrl(), r.website(), r.locationPreference(), status, message);
    }

    private static String urlError(String column, IllegalArgumentException e) {
        String message = e.getMessage();
        if (message != null && message.startsWith("URL is longer")) {
            return column + " is longer than " + UrlUtil.MAX_LENGTH + " characters";
        }
        if (message != null && message.startsWith("Host names containing")) {
            return column + ": host names containing \"_\" are not supported";
        }
        return column + " is not a valid URL";
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static void addIfPresent(Set<String> set, String value) {
        if (value != null) {
            set.add(value);
        }
    }
}
