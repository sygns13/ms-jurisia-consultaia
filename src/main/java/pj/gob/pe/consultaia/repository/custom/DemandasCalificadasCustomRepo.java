package pj.gob.pe.consultaia.repository.custom;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import pj.gob.pe.consultaia.model.entities.DemandasCalificadas;

import java.time.LocalDateTime;

public interface DemandasCalificadasCustomRepo {

    /**
     * Listado paginado de demandas calificadas filtrando siempre por userId, y opcionalmente por
     * rango de fechaSend, año de expediente (exacto) y número de expediente (LIKE).
     *
     * @param userId       usuario propietario (obtenido de la sesión); filtro obligatorio.
     * @param fechaDesde   límite inferior inclusivo de fechaSend (puede ser null).
     * @param fechaHasta   límite superior inclusivo de fechaSend (puede ser null).
     * @param anio         año del expediente, filtro exacto (puede ser null/blank).
     * @param expNro       número de expediente, filtro LIKE %expNro% (puede ser null/blank).
     * @param pageable     paginación y ordenamiento.
     */
    Page<DemandasCalificadas> listarPorFiltros(Long userId,
                                               LocalDateTime fechaDesde,
                                               LocalDateTime fechaHasta,
                                               String anio,
                                               String expNro,
                                               Pageable pageable);
}
