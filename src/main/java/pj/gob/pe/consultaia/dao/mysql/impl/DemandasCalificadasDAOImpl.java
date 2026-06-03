package pj.gob.pe.consultaia.dao.mysql.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import pj.gob.pe.consultaia.dao.mysql.DemandasCalificadasDAO;
import pj.gob.pe.consultaia.model.entities.DemandasCalificadas;
import pj.gob.pe.consultaia.repository.DemandasCalificadasRepo;
import pj.gob.pe.consultaia.repository.GenericRepo;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class DemandasCalificadasDAOImpl extends GenericDAOImpl<DemandasCalificadas, Long> implements DemandasCalificadasDAO {

    private final DemandasCalificadasRepo repo;

    @Override
    protected GenericRepo<DemandasCalificadas, Long> getRepo() {
        return repo;
    }

    @Override
    public Page<DemandasCalificadas> listarPorFiltros(Long userId,
                                                      LocalDateTime fechaDesde,
                                                      LocalDateTime fechaHasta,
                                                      String anio,
                                                      String expNro,
                                                      Pageable pageable) {
        return repo.listarPorFiltros(userId, fechaDesde, fechaHasta, anio, expNro, pageable);
    }

    @Override
    public Page<DemandasCalificadas> listarUltimaVersionPorNunico(Long userId,
                                                                 LocalDateTime fechaDesde,
                                                                 LocalDateTime fechaHasta,
                                                                 String anio,
                                                                 String expNro,
                                                                 Pageable pageable) {
        return repo.listarUltimaVersionPorNunico(userId, fechaDesde, fechaHasta, anio, expNro, pageable);
    }

    @Override
    public List<DemandasCalificadas> listarPorNunico(Long userId, Long nUnico) {
        return repo.listarPorNunico(userId, nUnico);
    }
}
