package pj.gob.pe.consultaia.service.business;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import pj.gob.pe.consultaia.utils.beans.inputs.InputDescargaSentencia;
import pj.gob.pe.consultaia.utils.beans.inputs.InputGeneracionSentencia;
import pj.gob.pe.consultaia.utils.beans.inputs.InputListadoDemandasSentencias;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseGeneracionSentencia;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseGeneracionSentenciaDocx;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseListadoDemandaSentencia;

import java.util.List;

public interface SentenciaService {

    ResponseGeneracionSentencia generarSentencia(InputGeneracionSentencia input, String sessionId) throws Exception;

    ResponseGeneracionSentenciaDocx generarSentenciaDocx(InputGeneracionSentencia input, String sessionId) throws Exception;

    Page<ResponseListadoDemandaSentencia> listarDemandasSentencias(InputListadoDemandasSentencias input,
                                                                   Pageable pageable,
                                                                   String sessionId) throws Exception;

    ResponseGeneracionSentenciaDocx descargarSentenciaDocx(InputDescargaSentencia input, String sessionId) throws Exception;

    Page<ResponseListadoDemandaSentencia> listarUltimaVersionDemandasSentencias(InputListadoDemandasSentencias input,
                                                                                Pageable pageable,
                                                                                String sessionId) throws Exception;

    List<ResponseListadoDemandaSentencia> listarSentenciasPorNunico(Long nUnico, String sessionId) throws Exception;
}
