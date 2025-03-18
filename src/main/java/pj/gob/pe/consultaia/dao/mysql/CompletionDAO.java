package pj.gob.pe.consultaia.dao.mysql;

import pj.gob.pe.consultaia.model.entities.Completions;

import java.util.List;
import java.util.Map;

public interface CompletionDAO extends GenericDAO<Completions, Long>{

    List<Completions> getConfigurationsByFilters(Map<String, Object> filters,
                                                 Map<String, Object> notEqualFilters,
                                                 Integer limit,
                                                 String orderByField);
}
