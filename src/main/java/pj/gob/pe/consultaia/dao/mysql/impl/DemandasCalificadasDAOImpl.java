package pj.gob.pe.consultaia.dao.mysql.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import pj.gob.pe.consultaia.dao.mysql.DemandasCalificadasDAO;
import pj.gob.pe.consultaia.model.entities.DemandasCalificadas;
import pj.gob.pe.consultaia.repository.DemandasCalificadasRepo;
import pj.gob.pe.consultaia.repository.GenericRepo;

@Repository
@RequiredArgsConstructor
public class DemandasCalificadasDAOImpl extends GenericDAOImpl<DemandasCalificadas, Long> implements DemandasCalificadasDAO {

    private final DemandasCalificadasRepo repo;

    @Override
    protected GenericRepo<DemandasCalificadas, Long> getRepo() {
        return repo;
    }
}
