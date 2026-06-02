package pj.gob.pe.consultaia.repository.custom.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import pj.gob.pe.consultaia.model.entities.DemandasCalificadas;
import pj.gob.pe.consultaia.repository.custom.DemandasCalificadasCustomRepo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class DemandasCalificadasCustomRepoImpl implements DemandasCalificadasCustomRepo {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<DemandasCalificadas> listarPorFiltros(Long userId,
                                                      LocalDateTime fechaDesde,
                                                      LocalDateTime fechaHasta,
                                                      String anio,
                                                      String expNro,
                                                      Pageable pageable) {

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        // ---- Consulta de datos paginados ----
        CriteriaQuery<DemandasCalificadas> query = cb.createQuery(DemandasCalificadas.class);
        Root<DemandasCalificadas> root = query.from(DemandasCalificadas.class);
        query.select(root).where(construirPredicados(cb, root, userId, fechaDesde, fechaHasta, anio, expNro));

        if (pageable.getSort().isSorted()) {
            List<Order> orders = new ArrayList<>();
            pageable.getSort().forEach(order ->
                    orders.add(order.isAscending() ? cb.asc(root.get(order.getProperty()))
                            : cb.desc(root.get(order.getProperty()))));
            query.orderBy(orders);
        }

        List<DemandasCalificadas> resultList = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        // ---- Consulta de total (count) con los mismos filtros ----
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<DemandasCalificadas> countRoot = countQuery.from(DemandasCalificadas.class);
        countQuery.select(cb.count(countRoot))
                .where(construirPredicados(cb, countRoot, userId, fechaDesde, fechaHasta, anio, expNro));

        Long totalElements = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(resultList, pageable, totalElements);
    }

    private Predicate[] construirPredicados(CriteriaBuilder cb,
                                            Root<DemandasCalificadas> root,
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
