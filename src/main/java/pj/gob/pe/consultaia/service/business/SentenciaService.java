package pj.gob.pe.consultaia.service.business;

import pj.gob.pe.consultaia.utils.beans.inputs.InputGeneracionSentencia;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseGeneracionSentencia;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseGeneracionSentenciaDocx;

public interface SentenciaService {

    ResponseGeneracionSentencia generarSentencia(InputGeneracionSentencia input, String sessionId) throws Exception;

    ResponseGeneracionSentenciaDocx generarSentenciaDocx(InputGeneracionSentencia input, String sessionId) throws Exception;
}
