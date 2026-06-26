package pj.gob.pe.consultaia.dao.mysql.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import pj.gob.pe.consultaia.dao.mysql.DemandasSentenciasDAO;
import pj.gob.pe.consultaia.model.entities.DemandasSentencias;
import pj.gob.pe.consultaia.repository.DemandasSentenciasRepo;
import pj.gob.pe.consultaia.repository.GenericRepo;

@Repository
@RequiredArgsConstructor
public class DemandasSentenciasDAOImpl extends GenericDAOImpl<DemandasSentencias, Long> implements DemandasSentenciasDAO {

    private final DemandasSentenciasRepo repo;

    @Override
    protected GenericRepo<DemandasSentencias, Long> getRepo() {
        return repo;
    }
}
