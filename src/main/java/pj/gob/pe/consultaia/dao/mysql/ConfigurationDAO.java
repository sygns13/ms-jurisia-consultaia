package pj.gob.pe.consultaia.dao.mysql;

import pj.gob.pe.consultaia.model.entities.Configurations;

import java.util.Map;

public interface ConfigurationDAO extends GenericDAO<Configurations, Long>{

    Configurations getConfigurationsByFilters(Map<String, Object> filters);
}
