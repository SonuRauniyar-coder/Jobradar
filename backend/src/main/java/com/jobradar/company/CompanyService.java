package com.jobradar.company;

import com.jobradar.common.ApiException;
import com.jobradar.common.UrlUtil;
import com.jobradar.company.CompanyDtos.CompanyDto;
import com.jobradar.company.CompanyDtos.CompanyRequest;
import com.jobradar.company.CompanyDtos.ListDto;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional
public class CompanyService {
    private final CompanyListRepository lists;
    private final CompanyRepository companies;

    public CompanyService(CompanyListRepository lists, CompanyRepository companies) {
        this.lists = lists;
        this.companies = companies;
    }

    public ListDto createList(Long userId, String name) {
        String trimmed = name.trim();
        if (lists.existsByUserIdAndNameIgnoreCase(userId, trimmed)) {
            throw new ApiException(HttpStatus.CONFLICT, "A list with this name already exists");
        }
        CompanyList list = new CompanyList();
        list.setUserId(userId);
        list.setName(trimmed);
        try {
            lists.saveAndFlush(list);
        } catch (DataIntegrityViolationException e) {
            // Another request created the same name between the check above and this insert.
            throw new ApiException(HttpStatus.CONFLICT, "A list with this name already exists");
        }
        return new ListDto(list.getId(), list.getName(), list.getCreatedAt(), 0);
    }

    @Transactional(readOnly = true)
    public List<ListDto> getLists(Long userId) {
        return lists.findByUserIdOrderByName(userId).stream()
                .map(l -> new ListDto(l.getId(), l.getName(), l.getCreatedAt(), companies.countByListId(l.getId())))
                .toList();
    }

    public void deleteList(Long userId, Long id) {
        lists.delete(lists.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("List")));
    }

    @Transactional(readOnly = true)
    public List<CompanyDto> getCompanies(Long userId, Long listId) {
        List<Company> result;
        if (listId == null) {
            result = companies.findByListUserIdOrderByName(userId);
        } else {
            requireList(userId, listId);
            result = companies.findByListIdOrderByName(listId);
        }
        return result.stream().map(CompanyDto::of).toList();
    }

    @Transactional(readOnly = true)
    public CompanyDto getCompany(Long userId, Long id) {
        return CompanyDto.of(require(userId, id));
    }

    public CompanyDto create(Long userId, CompanyRequest request) {
        if (request.listId() == null) {
            throw ApiException.badRequest("listId is required");
        }
        CompanyList list = lockList(userId, request.listId());
        assertCapacity(list);
        Company company = new Company();
        company.setList(list);
        apply(company, request);
        assertUnique(company);
        return CompanyDto.of(companies.save(company));
    }

    public CompanyDto update(Long userId, Long id, CompanyRequest request) {
        Company company = require(userId, id);
        String oldCareerUrl = company.getCareerUrl();
        if (request.listId() != null && !request.listId().equals(company.getList().getId())) {
            // Moving to another list: it must be one of this user's lists, with room left.
            CompanyList target = lockList(userId, request.listId());
            assertCapacity(target);
            company.setList(target);
        } else {
            lockList(userId, company.getList().getId());
        }
        apply(company, request);
        assertUnique(company);
        // A new URL makes earlier auto-detection stale.
        if (!company.isSourceLocked() && !java.util.Objects.equals(oldCareerUrl, company.getCareerUrl())) {
            company.setSourceType(SourceType.UNKNOWN);
            company.setAtsSlug(null);
        }
        return CompanyDto.of(company);
    }

    public void delete(Long userId, Long id) {
        companies.delete(require(userId, id));
    }

    private void apply(Company company, CompanyRequest request) {
        company.setName(request.name().trim());
        company.setLocationPreference(StringUtils.hasText(request.locationPreference())
                ? request.locationPreference().trim() : null);
        company.setWebsite(normalizeUrl("website", request.website()));
        company.setCareerUrl(normalizeUrl("careerUrl", request.careerUrl()));
        if (request.sourceType() != null && request.sourceType() != SourceType.UNKNOWN) {
            company.setSourceType(request.sourceType());
            company.setAtsSlug(StringUtils.hasText(request.atsSlug()) ? request.atsSlug().trim() : null);
            company.setSourceLocked(true);
        } else if (request.sourceType() == SourceType.UNKNOWN) {
            // Choosing "Auto-detect" hands the company back to discovery.
            company.setSourceType(SourceType.UNKNOWN);
            company.setAtsSlug(null);
            company.setSourceLocked(false);
        }
    }

    private static String normalizeUrl(String field, String value) {
        try {
            return UrlUtil.normalize(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(field + ": " + e.getMessage());
        }
    }

    private void assertUnique(Company company) {
        String name = company.getName().toLowerCase(Locale.ROOT);
        String domain = UrlUtil.domain(company.getWebsite());
        for (Company other : companies.findByListId(company.getList().getId())) {
            if (other.getId().equals(company.getId())) {
                continue;
            }
            boolean sameName = other.getName().toLowerCase(Locale.ROOT).equals(name);
            boolean sameDomain = domain != null && domain.equals(UrlUtil.domain(other.getWebsite()));
            if (sameName || sameDomain) {
                throw new ApiException(HttpStatus.CONFLICT, "\"" + other.getName() + "\" already uses this name or domain");
            }
        }
    }

    private void assertCapacity(CompanyList list) {
        if (companies.countByListId(list.getId()) >= CompanyImportService.MAX_COMPANIES_PER_LIST) {
            throw ApiException.badRequest("List limit of " + CompanyImportService.MAX_COMPANIES_PER_LIST + " companies reached");
        }
    }

    private CompanyList lockList(Long userId, Long listId) {
        return lists.findForUpdate(listId, userId).orElseThrow(() -> ApiException.notFound("List"));
    }

    private CompanyList requireList(Long userId, Long listId) {
        return lists.findByIdAndUserId(listId, userId).orElseThrow(() -> ApiException.notFound("List"));
    }

    private Company require(Long userId, Long id) {
        return companies.findByIdAndListUserId(id, userId).orElseThrow(() -> ApiException.notFound("Company"));
    }
}
