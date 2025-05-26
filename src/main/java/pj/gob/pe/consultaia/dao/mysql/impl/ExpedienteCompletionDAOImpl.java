package pj.gob.pe.consultaia.dao.mysql.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import pj.gob.pe.consultaia.dao.mysql.ExpedienteCompletionDAO;
import pj.gob.pe.consultaia.model.entities.ExpedienteCompletion;
import pj.gob.pe.consultaia.repository.ExpedienteCompletionRepo;
import pj.gob.pe.consultaia.repository.GenericRepo;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ExpedienteCompletionDAOImpl extends GenericDAOImpl<ExpedienteCompletion, Long> implements ExpedienteCompletionDAO {

    private final ExpedienteCompletionRepo repo;

    @Override
    protected GenericRepo<ExpedienteCompletion, Long> getRepo() {
        return repo;
    }

    @Override
    public List<ExpedienteCompletion> findExpedienteCompletions(Long nUnico, String codeTemplate){
        return repo.findExpedienteCompletions(nUnico, codeTemplate);
    }
}
