package pj.gob.pe.consultaia.repository.custom.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import pj.gob.pe.consultaia.model.entities.DemandasSentencias;
import pj.gob.pe.consultaia.repository.custom.DemandasSentenciasCustomRepo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class DemandasSentenciasCustomRepoImpl implements DemandasSentenciasCustomRepo {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<DemandasSentencias> listarPorFiltros(Long userId,
                                                     LocalDateTime fechaDesde,
                                                     LocalDateTime fechaHasta,
                                                     String anio,
                                                     String expNro,
                                                     Pageable pageable) {

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        // ---- Consulta de datos paginados ----
        CriteriaQuery<DemandasSentencias> query = cb.createQuery(DemandasSentencias.class);
        Root<DemandasSentencias> root = query.from(DemandasSentencias.class);
        query.select(root).where(construirPredicados(cb, root, userId, fechaDesde, fechaHasta, anio, expNro));

        if (pageable.getSort().isSorted()) {
            List<Order> orders = new ArrayList<>();
            pageable.getSort().forEach(order ->
                    orders.add(order.isAscending() ? cb.asc(root.get(order.getProperty()))
                            : cb.desc(root.get(order.getProperty()))));
            query.orderBy(orders);
        }

        List<DemandasSentencias> resultList = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        // ---- Consulta de total (count) con los mismos filtros ----
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<DemandasSentencias> countRoot = countQuery.from(DemandasSentencias.class);
        countQuery.select(cb.count(countRoot))
                .where(construirPredicados(cb, countRoot, userId, fechaDesde, fechaHasta, anio, expNro));

        Long totalElements = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(resultList, pageable, totalElements);
    }

    @Override
    public Page<DemandasSentencias> listarUltimaVersionPorNunico(Long userId,
                                                                 LocalDateTime fechaDesde,
                                                                 LocalDateTime fechaHasta,
                                                                 String anio,
                                                                 String expNro,
                                                                 Pageable pageable) {

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        // ---- Consulta de datos paginados ----
        // Por cada nUnico se conserva únicamente el registro de mayor id (la última versión)
        // dentro del conjunto que cumple los filtros, vía subconsulta MAX(id) GROUP BY nUnico.
        CriteriaQuery<DemandasSentencias> query = cb.createQuery(DemandasSentencias.class);
        Root<DemandasSentencias> root = query.from(DemandasSentencias.class);

        Subquery<Long> subquery = query.subquery(Long.class);
        Root<DemandasSentencias> subRoot = subquery.from(DemandasSentencias.class);
        subquery.select(cb.max(subRoot.get("id")))
                .where(construirPredicados(cb, subRoot, userId, fechaDesde, fechaHasta, anio, expNro))
                .groupBy(subRoot.get("nUnico"));

        query.select(root).where(cb.in(root.get("id")).value(subquery));

        if (pageable.getSort().isSorted()) {
            List<Order> orders = new ArrayList<>();
            pageable.getSort().forEach(order ->
                    orders.add(order.isAscending() ? cb.asc(root.get(order.getProperty()))
                            : cb.desc(root.get(order.getProperty()))));
            query.orderBy(orders);
        }

        List<DemandasSentencias> resultList = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        // ---- Total de grupos (un nUnico = un elemento) con los mismos filtros ----
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<DemandasSentencias> countRoot = countQuery.from(DemandasSentencias.class);
        countQuery.select(cb.countDistinct(countRoot.get("nUnico")))
                .where(construirPredicados(cb, countRoot, userId, fechaDesde, fechaHasta, anio, expNro));

        Long totalElements = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(resultList, pageable, totalElements);
    }

    @Override
    public List<DemandasSentencias> listarPorNunico(Long userId, Long nUnico) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<DemandasSentencias> query = cb.createQuery(DemandasSentencias.class);
        Root<DemandasSentencias> root = query.from(DemandasSentencias.class);

        query.select(root)
                .where(
                        cb.equal(root.get("userId"), userId),
                        cb.equal(root.get("nUnico"), nUnico)
                )
                .orderBy(cb.desc(root.get("id"))); // del más nuevo al más antiguo

        return entityManager.createQuery(query).getResultList();
    }

    private Predicate[] construirPredicados(CriteriaBuilder cb,
                                            Root<DemandasSentencias> root,
                                            Long userId,
                                            LocalDateTime fechaDesde,
                                            LocalDateTime fechaHasta,
                                            String anio,
                                            String expNro) {
        List<Predicate> predicates = new ArrayList<>();

        // userId siempre (filtro obligatorio)
        predicates.add(cb.equal(root.get("userId"), userId));

        // Rango de fechaSend (datetime) a partir de fechas (date)
        if (fechaDesde != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("fechaSend"), fechaDesde));
        }
        if (fechaHasta != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("fechaSend"), fechaHasta));
        }

        // Año (exacto)
        if (anio != null && !anio.isBlank()) {
            predicates.add(cb.equal(root.get("anio"), anio));
        }

        // Número de expediente (LIKE)
        if (expNro != null && !expNro.isBlank()) {
            predicates.add(cb.like(root.get("expNro"), "%" + expNro + "%"));
        }

        return predicates.toArray(new Predicate[0]);
    }
}
