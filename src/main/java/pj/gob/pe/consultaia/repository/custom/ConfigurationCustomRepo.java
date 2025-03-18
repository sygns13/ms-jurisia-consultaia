package pj.gob.pe.consultaia.repository.custom;

import pj.gob.pe.consultaia.model.entities.Configurations;

import java.util.Map;

public interface ConfigurationCustomRepo {

    Configurations getConfigurationsByFilters(Map<String, Object> filters);
}
