package pj.gob.pe.consultaia.repository.custom;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import pj.gob.pe.consultaia.model.entities.DemandasSentencias;

import java.time.LocalDateTime;
import java.util.List;

public interface DemandasSentenciasCustomRepo {

    /**
     * Listado paginado de sentencias de demanda generadas filtrando siempre por userId, y
     * opcionalmente por rango de fechaSend, año de expediente (exacto) y número de expediente (LIKE).
     *
     * @param userId       usuario propietario (obtenido de la sesión); filtro obligatorio.
     * @param fechaDesde   límite inferior inclusivo de fechaSend (puede ser null).
     * @param fechaHasta   límite superior inclusivo de fechaSend (puede ser null).
     * @param anio         año del expediente, filtro exacto (puede ser null/blank).
     * @param expNro       número de expediente, filtro LIKE %expNro% (puede ser null/blank).
     * @param pageable     paginación y ordenamiento.
     */
    Page<DemandasSentencias> listarPorFiltros(Long userId,
                                              LocalDateTime fechaDesde,
                                              LocalDateTime fechaHasta,
                                              String anio,
                                              String expNro,
                                              Pageable pageable);

    /**
     * Listado paginado de la ÚLTIMA versión de cada sentencia generada (agrupado por nUnico).
     * Aplica los mismos filtros que {@link #listarPorFiltros}, pero por cada nUnico devuelve un
     * único registro: el de mayor id (último registro realizado) dentro del conjunto filtrado.
     */
    Page<DemandasSentencias> listarUltimaVersionPorNunico(Long userId,
                                                          LocalDateTime fechaDesde,
                                                          LocalDateTime fechaHasta,
                                                          String anio,
                                                          String expNro,
                                                          Pageable pageable);

    /**
     * Lista TODAS las versiones (registros) de sentencias de una demanda identificada por nUnico,
     * filtrando siempre por userId. Ordenado por id DESC (del más nuevo al más antiguo). Sin paginación.
     */
    List<DemandasSentencias> listarPorNunico(Long userId, Long nUnico);
}
