package pj.gob.pe.consultaia.dao.mysql.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import pj.gob.pe.consultaia.dao.mysql.CompletionDAO;
import pj.gob.pe.consultaia.model.entities.Completions;
import pj.gob.pe.consultaia.repository.CompletionRepo;
import pj.gob.pe.consultaia.repository.GenericRepo;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class CompletionDAOImpl extends GenericDAOImpl<Completions, Long> implements CompletionDAO {

    private final CompletionRepo repo;

    @Override
    protected GenericRepo<Completions, Long> getRepo() {
        return repo;
    }

    @Override
    public List<Completions> getCompletionsByFilters(Map<String, Object> filters, Map<String, Object> notEqualFilters, Integer limit, String orderByField) {
        return repo.getCompletionsByFilters(filters, notEqualFilters, limit, orderByField);
    }

    @Override
    public Page<Completions> getGralCompletionsByFilters(Map<String, Object> filters, Map<String, Object> notEqualFilters, Pageable pageable) {
        return repo.getGralCompletionsByFilters(filters, notEqualFilters, pageable);
    }

    @Override
    public Long getTotalConversaciones(
            Map<String, Object> filters,
            Map<String, Object> notEqualFilters){
        return repo.getTotalConversaciones(filters, notEqualFilters);
    }
}
