package pj.gob.pe.consultaia.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pj.gob.pe.consultaia.model.entities.GeminiExpedienteChats;

import java.util.List;

public interface GeminiExpedienteChatsRepo extends GenericRepo<GeminiExpedienteChats, Long> {

    /**
     * Correcciones previas del expediente/plantilla dentro de la ventana de reutilización de
     * 7 días, misma regla que el flujo OpenAI (ExpedienteCompletions).
     */
    @Query(
            value = "select * from JURISDB_CONSULTATIONIA.GeminiExpedienteChats " +
                    "where nUnico = :n_unico and templateCode = :template_code " +
                    "and fechaResponse >= NOW() - INTERVAL 7 DAY;",
            nativeQuery = true
    )
    List<GeminiExpedienteChats> findGeminiExpedienteChats(@Param("n_unico") Long nUnico,
                                                          @Param("template_code") String codeTemplate);
}
