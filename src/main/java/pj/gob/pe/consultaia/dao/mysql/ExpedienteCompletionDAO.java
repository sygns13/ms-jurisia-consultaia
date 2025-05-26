package pj.gob.pe.consultaia.dao.mysql;

import org.springframework.data.repository.query.Param;
import pj.gob.pe.consultaia.model.entities.ExpedienteCompletion;

import java.util.List;

public interface ExpedienteCompletionDAO extends GenericDAO<ExpedienteCompletion, Long>{

    List<ExpedienteCompletion> findExpedienteCompletions(Long nUnico, String codeTemplate);
}
