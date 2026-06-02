package pj.gob.pe.consultaia.dao.mysql;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import pj.gob.pe.consultaia.model.entities.DemandasCalificadas;

import java.time.LocalDateTime;

public interface DemandasCalificadasDAO extends GenericDAO<DemandasCalificadas, Long> {

    Page<DemandasCalificadas> listarPorFiltros(Long userId,
                                               LocalDateTime fechaDesde,
                                               LocalDateTime fechaHasta,
                                               String anio,
                                               String expNro,
                                               Pageable pageable);
}
