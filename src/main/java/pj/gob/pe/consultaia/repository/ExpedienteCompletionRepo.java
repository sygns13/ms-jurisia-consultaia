package pj.gob.pe.consultaia.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pj.gob.pe.consultaia.model.entities.ExpedienteCompletion;
import pj.gob.pe.consultaia.repository.custom.CompletionCustomRepo;

import java.util.List;

public interface ExpedienteCompletionRepo extends GenericRepo<ExpedienteCompletion, Long> {

    @Query(
            value = "select * from JURISDB_CONSULTATIONIA.ExpedienteCompletions where nUnico = :n_unico and templateCode = :template_code and fechaResponse >= NOW() - INTERVAL 7 DAY;",
            nativeQuery = true
    )
    List<ExpedienteCompletion> findExpedienteCompletions(@Param("n_unico") Long nUnico, @Param("template_code") String codeTemplate);
}
