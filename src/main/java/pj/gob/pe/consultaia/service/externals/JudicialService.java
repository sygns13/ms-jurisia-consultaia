package pj.gob.pe.consultaia.service.externals;

import pj.gob.pe.consultaia.utils.beans.responses.DataInstanciaDTO;
import pj.gob.pe.consultaia.utils.beans.responses.DataSedeDTO;

import java.util.List;

public interface JudicialService {

    List<DataSedeDTO> GetSedes(String SessionId);
    List<DataInstanciaDTO> GetInstancias(String SessionId);
}
