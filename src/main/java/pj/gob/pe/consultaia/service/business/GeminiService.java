package pj.gob.pe.consultaia.service.business;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import pj.gob.pe.consultaia.utils.beans.inputs.InputCalificacionDemanda;
import pj.gob.pe.consultaia.utils.beans.inputs.InputDescargaCalificacion;
import pj.gob.pe.consultaia.utils.beans.inputs.InputListadoDemandasCalificadas;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseCalificacionDemanda;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseCalificacionDemandaDocx;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseListadoDemandaCalificada;

import java.util.List;

public interface GeminiService {

    ResponseCalificacionDemanda calificarDemanda(InputCalificacionDemanda input, String sessionId) throws Exception;

    ResponseCalificacionDemandaDocx calificarDemandaDocx(InputCalificacionDemanda input, String sessionId) throws Exception;

    Page<ResponseListadoDemandaCalificada> listarDemandasCalificadas(InputListadoDemandasCalificadas input,
                                                                     Pageable pageable,
                                                                     String sessionId) throws Exception;

    ResponseCalificacionDemandaDocx descargarCalificacionDocx(InputDescargaCalificacion input, String sessionId) throws Exception;

    Page<ResponseListadoDemandaCalificada> listarUltimaVersionDemandasCalificadas(InputListadoDemandasCalificadas input,
                                                                                  Pageable pageable,
                                                                                  String sessionId) throws Exception;

    List<ResponseListadoDemandaCalificada> listarCalificacionesPorNunico(Long nUnico, String sessionId) throws Exception;
}
