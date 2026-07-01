package pj.gob.pe.consultaia.dao.mysql;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import pj.gob.pe.consultaia.model.entities.DemandasSentencias;

import java.time.LocalDateTime;
import java.util.List;

public interface DemandasSentenciasDAO extends GenericDAO<DemandasSentencias, Long> {

    Page<DemandasSentencias> listarPorFiltros(Long userId,
                                              LocalDateTime fechaDesde,
                                              LocalDateTime fechaHasta,
                                              String anio,
                                              String expNro,
                                              Pageable pageable);

    Page<DemandasSentencias> listarUltimaVersionPorNunico(Long userId,
                                                          LocalDateTime fechaDesde,
                                                          LocalDateTime fechaHasta,
                                                          String anio,
                                                          String expNro,
                                                          Pageable pageable);

    List<DemandasSentencias> listarPorNunico(Long userId, Long nUnico);
}
