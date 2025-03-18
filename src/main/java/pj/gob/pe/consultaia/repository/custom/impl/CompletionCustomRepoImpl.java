package pj.gob.pe.consultaia.repository.custom.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Repository;
import pj.gob.pe.consultaia.model.entities.Completions;
import pj.gob.pe.consultaia.model.entities.Configurations;
import pj.gob.pe.consultaia.repository.custom.CompletionCustomRepo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class CompletionCustomRepoImpl implements CompletionCustomRepo {

    @PersistenceContext
    private EntityManager entityManager;

    
    @Override
    public List<Completions> getConfigurationsByFilters(Map<String, Object> filters,
                                                        Map<String, Object> notEqualFilters,
                                                        Integer limit,
                                                        String orderByField) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Completions> query = cb.createQuery(Completions.class);
        Root<Completions> completions = query.from(Completions.class);

        // Lista para predicados que se combinarán con OR
        List<Predicate> orPredicates = new ArrayList<>();

        // Lista para predicados que se combinarán con AND
        List<Predicate> andPredicates = new ArrayList<>();

        // Lista para predicados que se combinarán con AND y representan desigualdades (!=)
        List<Predicate> notEqualPredicates = new ArrayList<>();


        // Agregar filtros dinámicos según los valores en el Map
        filters.forEach((key, value) -> {
            if (value != null) {
                // Aquí decides qué filtros van con OR y cuáles con AND
                if (key.startsWith("or_")) {
                    // Filtros que se combinarán con OR
                    orPredicates.add(cb.equal(completions.get(key.replace("or_", "")), value));
                } else {
                    // Filtros que se combinarán con AND
                    andPredicates.add(cb.equal(completions.get(key), value));
                }
            }
        });

        // Agregar filtros dinámicos que representan desigualdades (!=)
        notEqualFilters.forEach((key, value) -> {
            if (value != null) {
                notEqualPredicates.add(cb.notEqual(completions.get(key), value));
            }
        });

        // Combinar los predicados OR en un solo predicado
        Predicate orPredicate = orPredicates.isEmpty() ? null : cb.or(orPredicates.toArray(new Predicate[0]));

        // Combinar los predicados AND en un solo predicado
        Predicate andPredicate = andPredicates.isEmpty() ? null : cb.and(andPredicates.toArray(new Predicate[0]));

        // Combinar los predicados NOT EQUAL en un solo predicado
        Predicate notEqualPredicate = notEqualPredicates.isEmpty() ? null : cb.and(notEqualPredicates.toArray(new Predicate[0]));

        // Combinar todos los predicados en un predicado final
        Predicate finalPredicate = cb.and(
                orPredicate != null ? orPredicate : cb.conjunction(), // Si no hay OR, usar conjunción (true)
                andPredicate != null ? andPredicate : cb.conjunction(), // Si no hay AND, usar conjunción (true)
                notEqualPredicate != null ? notEqualPredicate : cb.conjunction() // Si no hay NOT EQUAL, usar conjunción (true)
        );

        // Aplicar el predicado final a la consulta
        query.select(completions).where(finalPredicate);

        // Ordenar los resultados por un campo específico en orden descendente
        if (orderByField != null && !orderByField.isEmpty()) {
            query.orderBy(cb.desc(completions.get(orderByField))); // Orden descendente
        }


        // Obtener los resultados con un límite
        return entityManager.createQuery(query)
                .setMaxResults(limit) // Aplicar el límite
                .getResultList();
    }
}
