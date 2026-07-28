package pj.gob.pe.consultaia.repository.custom.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import pj.gob.pe.consultaia.model.entities.GeminiChats;
import pj.gob.pe.consultaia.repository.custom.GeminiChatsCustomRepo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Consultas dinámicas sobre GeminiChats. Mismo patrón de filtros por Criteria que
 * {@link CompletionCustomRepoImpl} (historial por sessionUID, listado de conversaciones
 * agrupado por sessionUID y total de conversaciones).
 */
@Repository
public class GeminiChatsCustomRepoImpl implements GeminiChatsCustomRepo {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<GeminiChats> getGeminiChatsByFilters(Map<String, Object> filters,
                                                     Map<String, Object> notEqualFilters,
                                                     Integer limit,
                                                     String orderByField) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<GeminiChats> query = cb.createQuery(GeminiChats.class);
        Root<GeminiChats> chats = query.from(GeminiChats.class);

        Predicate finalPredicate = buildPredicate(chats, cb, filters, notEqualFilters);

        query.select(chats).where(finalPredicate);

        // Ordenar los resultados por un campo específico en orden descendente
        if (orderByField != null && !orderByField.isEmpty()) {
            query.orderBy(cb.desc(chats.get(orderByField)));
        }

        return entityManager.createQuery(query)
                .setMaxResults(limit)
                .getResultList();
    }

    @Override
    public Page<GeminiChats> getGralGeminiChatsByFilters(
            Map<String, Object> filters,
            Map<String, Object> notEqualFilters,
            Pageable pageable) {

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        // Consulta principal: un registro (el primero, min(id)) por cada conversación (sessionUID)
        CriteriaQuery<GeminiChats> query = cb.createQuery(GeminiChats.class);
        Root<GeminiChats> chats = query.from(GeminiChats.class);

        Predicate mainPredicate = buildPredicate(chats, cb, filters, notEqualFilters);
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<GeminiChats> subRoot = subquery.from(GeminiChats.class);
        Predicate subPredicate = buildPredicate(subRoot, cb, filters, notEqualFilters);

        subquery.select(cb.min(subRoot.get("id")))
                .where(subPredicate)
                .groupBy(subRoot.get("sessionUID"));

        Predicate finalPredicate = cb.and(
                mainPredicate,
                cb.in(chats.get("id")).value(subquery)
        );

        query.select(chats).where(finalPredicate);

        // Aplicar ordenamiento desde Pageable
        if (pageable.getSort().isSorted()) {
            List<Order> orders = new ArrayList<>();
            pageable.getSort().forEach(order -> {
                if (order.isAscending()) {
                    orders.add(cb.asc(chats.get(order.getProperty())));
                } else {
                    orders.add(cb.desc(chats.get(order.getProperty())));
                }
            });
            query.orderBy(orders);
        }

        List<GeminiChats> resultList = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        // Total de conversaciones (sessionUID distintos) que cumplen los filtros
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<GeminiChats> countRoot = countQuery.from(GeminiChats.class);
        Predicate countPredicate = buildPredicate(countRoot, cb, filters, notEqualFilters);

        countQuery.select(cb.countDistinct(countRoot.get("sessionUID")))
                .where(countPredicate);

        Long totalElements = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(resultList, pageable, totalElements);
    }

    @Override
    public Long getTotalConversaciones(
            Map<String, Object> filters,
            Map<String, Object> notEqualFilters) {

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<GeminiChats> countRoot = countQuery.from(GeminiChats.class);
        Predicate countPredicate = buildPredicate(countRoot, cb, filters, notEqualFilters);

        countQuery.select(cb.countDistinct(countRoot.get("sessionUID")))
                .where(countPredicate);

        return entityManager.createQuery(countQuery).getSingleResult();
    }

    // Método helper para construir predicados dinámicos (mismas convenciones or_/AND/!= del proyecto)
    private Predicate buildPredicate(
            Root<GeminiChats> root,
            CriteriaBuilder cb,
            Map<String, Object> filters,
            Map<String, Object> notEqualFilters) {

        List<Predicate> orPredicates = new ArrayList<>();
        List<Predicate> andPredicates = new ArrayList<>();
        List<Predicate> notEqualPredicates = new ArrayList<>();

        filters.forEach((key, value) -> {
            if (value != null) {
                if (key.startsWith("or_")) {
                    orPredicates.add(cb.equal(root.get(key.replace("or_", "")), value));
                } else {
                    andPredicates.add(cb.equal(root.get(key), value));
                }
            }
        });

        notEqualFilters.forEach((key, value) -> {
            if (value != null) {
                notEqualPredicates.add(cb.notEqual(root.get(key), value));
            }
        });

        Predicate orPredicate = orPredicates.isEmpty() ? cb.conjunction() : cb.or(orPredicates.toArray(new Predicate[0]));
        Predicate andPredicate = andPredicates.isEmpty() ? cb.conjunction() : cb.and(andPredicates.toArray(new Predicate[0]));
        Predicate notEqualPredicate = notEqualPredicates.isEmpty() ? cb.conjunction() : cb.and(notEqualPredicates.toArray(new Predicate[0]));

        return cb.and(orPredicate, andPredicate, notEqualPredicate);
    }
}
