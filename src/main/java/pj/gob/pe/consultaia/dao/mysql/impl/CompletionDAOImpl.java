package pj.gob.pe.consultaia.dao.mysql.impl;

import lombok.RequiredArgsConstructor;
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
    public List<Completions> getConfigurationsByFilters(Map<String, Object> filters, Map<String, Object> notEqualFilters, Integer limit, String orderByField) {
        return repo.getConfigurationsByFilters(filters, notEqualFilters, limit, orderByField);
    }
}
