package pj.gob.pe.consultaia.service.business;

import pj.gob.pe.consultaia.utils.beans.inputs.InputDocumentoPlantilla;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseDocumentoPlantilla;

public interface GeminiDocumentoPlantillaService {

    ResponseDocumentoPlantilla procesarDocumentoPlantilla(InputDocumentoPlantilla input, String SessionId) throws Exception;
}
