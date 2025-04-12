package pj.gob.pe.consultaia.repository.custom.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
    public List<Completions> getCompletionsByFilters(Map<String, Object> filters,
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

    @Override
    public Page<Completions> getGralCompletionsByFilters(
            Map<String, Object> filters,
            Map<String, Object> notEqualFilters,
            Pageable pageable) {

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        // Consulta principal para obtener los datos paginados
        CriteriaQuery<Completions> query = cb.createQuery(Completions.class);
        Root<Completions> completions = query.from(Completions.class);

        // Construir predicados y subconsulta (similar al código anterior)
        Predicate mainPredicate = buildPredicate(completions, cb, filters, notEqualFilters);
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<Completions> subRoot = subquery.from(Completions.class);
        Predicate subPredicate = buildPredicate(subRoot, cb, filters, notEqualFilters);

        subquery.select(cb.min(subRoot.get("id")))
                .where(subPredicate)
                .groupBy(subRoot.get("sessionUID"));

        Predicate finalPredicate = cb.and(
                mainPredicate,
                cb.in(completions.get("id")).value(subquery)
        );

        query.select(completions).where(finalPredicate);

        // Aplicar ordenamiento desde Pageable
        if (pageable.getSort().isSorted()) {
            List<Order> orders = new ArrayList<>();
            pageable.getSort().forEach(order -> {
                if (order.isAscending()) {
                    orders.add(cb.asc(completions.get(order.getProperty())));
                } else {
                    orders.add(cb.desc(completions.get(order.getProperty())));
                }
            });
            query.orderBy(orders);
        }

        // Ejecutar consulta paginada
        List<Completions> resultList = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        // Consulta para obtener el total de elementos (count)
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Completions> countRoot = countQuery.from(Completions.class);
        Predicate countPredicate = buildPredicate(countRoot, cb, filters, notEqualFilters);

        // Contar el total de grupos únicos de "session" que cumplen los filtros
        countQuery.select(cb.countDistinct(countRoot.get("sessionUID"))) // ¡Clave aquí!
                .where(countPredicate);

        Long totalElements = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(resultList, pageable, totalElements);
    }

    // Método helper para construir predicados dinámicos
    private Predicate buildPredicate(
            Root<Completions> root,
            CriteriaBuilder cb,
            Map<String, Object> filters,
            Map<String, Object> notEqualFilters) {

        List<Predicate> orPredicates = new ArrayList<>();
        List<Predicate> andPredicates = new ArrayList<>();
        List<Predicate> notEqualPredicates = new ArrayList<>();

        // Procesar filtros "OR" y "AND"
        filters.forEach((key, value) -> {
            if (value != null) {
                if (key.startsWith("or_")) {
                    orPredicates.add(cb.equal(root.get(key.replace("or_", "")), value));
                } else {
                    andPredicates.add(cb.equal(root.get(key), value));
                }
            }
        });

        // Procesar filtros "NOT EQUAL"
        notEqualFilters.forEach((key, value) -> {
            if (value != null) {
                notEqualPredicates.add(cb.notEqual(root.get(key), value));
            }
        });

        // Combinar todos los predicados
        Predicate orPredicate = orPredicates.isEmpty() ? cb.conjunction() : cb.or(orPredicates.toArray(new Predicate[0]));
        Predicate andPredicate = andPredicates.isEmpty() ? cb.conjunction() : cb.and(andPredicates.toArray(new Predicate[0]));
        Predicate notEqualPredicate = notEqualPredicates.isEmpty() ? cb.conjunction() : cb.and(notEqualPredicates.toArray(new Predicate[0]));

        return cb.and(orPredicate, andPredicate, notEqualPredicate);
    }
}
