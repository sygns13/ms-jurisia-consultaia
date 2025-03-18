package pj.gob.pe.consultaia.dao.mysql.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import pj.gob.pe.consultaia.dao.mysql.ConfigurationDAO;
import pj.gob.pe.consultaia.model.entities.Configurations;
import pj.gob.pe.consultaia.repository.ConfigurationRepo;
import pj.gob.pe.consultaia.repository.GenericRepo;

import java.util.Map;

@Repository
@RequiredArgsConstructor
public class ConfigurationDAOImpl extends GenericDAOImpl<Configurations, Long> implements ConfigurationDAO {

    private final ConfigurationRepo repo;

    @Override
    protected GenericRepo<Configurations, Long> getRepo() {
        return repo;
    }

    @Override
    public Configurations getConfigurationsByFilters(Map<String, Object> filters) {
        return repo.getConfigurationsByFilters(filters);
    }
}
