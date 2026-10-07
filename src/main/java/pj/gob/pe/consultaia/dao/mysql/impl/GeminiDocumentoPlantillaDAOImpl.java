package pj.gob.pe.consultaia.dao.mysql.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import pj.gob.pe.consultaia.dao.mysql.GeminiDocumentoPlantillaDAO;
import pj.gob.pe.consultaia.model.entities.GeminiDocumentoPlantilla;
import pj.gob.pe.consultaia.repository.GeminiDocumentoPlantillaRepo;
import pj.gob.pe.consultaia.repository.GenericRepo;

@Repository
@RequiredArgsConstructor
public class GeminiDocumentoPlantillaDAOImpl extends GenericDAOImpl<GeminiDocumentoPlantilla, Long> implements GeminiDocumentoPlantillaDAO {

    private final GeminiDocumentoPlantillaRepo repo;

    @Override
    protected GenericRepo<GeminiDocumentoPlantilla, Long> getRepo() {
        return repo;
    }
}
