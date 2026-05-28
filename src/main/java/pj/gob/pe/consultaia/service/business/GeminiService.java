package pj.gob.pe.consultaia.service.business;

import pj.gob.pe.consultaia.utils.beans.inputs.InputCalificacionDemanda;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseCalificacionDemanda;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseCalificacionDemandaDocx;

public interface GeminiService {

    ResponseCalificacionDemanda calificarDemanda(InputCalificacionDemanda input, String sessionId) throws Exception;

    ResponseCalificacionDemandaDocx calificarDemandaDocx(InputCalificacionDemanda input, String sessionId) throws Exception;
}
