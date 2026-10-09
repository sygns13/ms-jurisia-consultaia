package pj.gob.pe.consultaia.service.externals;

import pj.gob.pe.consultaia.utils.beans.responses.DataInstanciaDTO;
import pj.gob.pe.consultaia.utils.beans.responses.DataSedeDTO;

import java.util.List;

public interface JudicialService {

    List<DataSedeDTO> GetSedes(String SessionId);
    List<DataInstanciaDTO> GetInstancias(String SessionId);

    /**
     * Resuelve la sede SIJ a la que pertenece una instancia, cruzando las instancias y sedes del
     * usuario de la sesión. Best-effort: devuelve null si no se encuentra o si falla la consulta.
     */
    DataSedeDTO GetSedePorInstancia(String SessionId, String cinstancia);
}
