package pj.gob.pe.consultaia.dao.mysql.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import pj.gob.pe.consultaia.dao.mysql.DemandasSentenciasDAO;
import pj.gob.pe.consultaia.model.entities.DemandasSentencias;
import pj.gob.pe.consultaia.repository.DemandasSentenciasRepo;
import pj.gob.pe.consultaia.repository.GenericRepo;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class DemandasSentenciasDAOImpl extends GenericDAOImpl<DemandasSentencias, Long> implements DemandasSentenciasDAO {

    private final DemandasSentenciasRepo repo;

    @Override
    protected GenericRepo<DemandasSentencias, Long> getRepo() {
        return repo;
    }

    @Override
    public Page<DemandasSentencias> listarPorFiltros(Long userId,
                                                     LocalDateTime fechaDesde,
                                                     LocalDateTime fechaHasta,
                                                     String anio,
                                                     String expNro,
                                                     Pageable pageable) {
        return repo.listarPorFiltros(userId, fechaDesde, fechaHasta, anio, expNro, pageable);
    }

    @Override
    public Page<DemandasSentencias> listarUltimaVersionPorNunico(Long userId,
                                                                 LocalDateTime fechaDesde,
                                                                 LocalDateTime fechaHasta,
                                                                 String anio,
                                                                 String expNro,
                                                                 Pageable pageable) {
        return repo.listarUltimaVersionPorNunico(userId, fechaDesde, fechaHasta, anio, expNro, pageable);
    }

    @Override
    public List<DemandasSentencias> listarPorNunico(Long userId, Long nUnico) {
        return repo.listarPorNunico(userId, nUnico);
    }
}
