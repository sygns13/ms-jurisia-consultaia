package pj.gob.pe.consultaia.repository.custom.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Repository;
import pj.gob.pe.consultaia.model.entities.Configurations;
import pj.gob.pe.consultaia.repository.custom.ConfigurationCustomRepo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class ConfigurationCustomRepoImpl implements ConfigurationCustomRepo {

    @PersistenceContext
    private EntityManager entityManager;


    @Override
    public Configurations getConfigurationsByFilters(Map<String, Object> filters) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Configurations> query = cb.createQuery(Configurations.class);
        Root<Configurations> configurations = query.from(Configurations.class);

        List<Predicate> predicates = new ArrayList<>();

        // Agregar filtros dinámicos según los valores en el Map
        filters.forEach((key, value) -> {
            if (value != null) {
                predicates.add(cb.equal(configurations.get(key), value));
            }
        });

        // WHERE borrado = 0 (para excluir registros eliminados lógicamente)
        //predicates.add(cb.equal(configurations.get("borrado"), 0));

        query.select(configurations).where(predicates.toArray(new Predicate[0]));
        return entityManager.createQuery(query).getResultStream().findFirst().orElse(null);

    }
}
