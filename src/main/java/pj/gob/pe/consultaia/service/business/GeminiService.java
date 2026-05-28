package pj.gob.pe.consultaia.service.business;

import pj.gob.pe.consultaia.utils.beans.inputs.InputCalificacionDemanda;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseCalificacionDemanda;

public interface GeminiService {

    ResponseCalificacionDemanda calificarDemanda(InputCalificacionDemanda input, String sessionId) throws Exception;
}
