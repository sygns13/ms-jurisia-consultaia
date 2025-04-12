package pj.gob.pe.consultaia.dao.mysql;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import pj.gob.pe.consultaia.model.entities.Completions;

import java.util.List;
import java.util.Map;

public interface CompletionDAO extends GenericDAO<Completions, Long>{

    List<Completions> getCompletionsByFilters(Map<String, Object> filters,
                                                 Map<String, Object> notEqualFilters,
                                                 Integer limit,
                                                 String orderByField);

    Page<Completions> getGralCompletionsByFilters(
            Map<String, Object> filters,
            Map<String, Object> notEqualFilters,
            Pageable pageable);
}
