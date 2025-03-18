package pj.gob.pe.consultaia.repository.custom;

import pj.gob.pe.consultaia.model.entities.Completions;

import java.util.List;
import java.util.Map;

public interface CompletionCustomRepo {

    List<Completions> getConfigurationsByFilters(Map<String, Object> filters,
                                                 Map<String, Object> notEqualFilters,
                                                 Integer limit,
                                                 String orderByField);
}
