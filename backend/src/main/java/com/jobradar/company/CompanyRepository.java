package com.jobradar.company;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {
    List<Company> findByListId(Long listId);
    List<Company> findByListUserIdOrderByName(Long userId);
    List<Company> findByListIdOrderByName(Long listId);
    List<Company> findByListIdAndListUserIdOrderByName(Long listId, Long userId);
    List<Company> findByIdInAndListUserId(Collection<Long> ids, Long userId);
    Optional<Company> findByIdAndListUserId(Long id, Long userId);
    long countByListId(Long listId);
    long countByListUserId(Long userId);
}
