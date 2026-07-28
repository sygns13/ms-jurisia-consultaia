package pj.gob.pe.consultaia.dao.mysql;

import pj.gob.pe.consultaia.model.entities.GeminiExpedienteChats;

import java.util.List;

public interface GeminiExpedienteChatsDAO extends GenericDAO<GeminiExpedienteChats, Long> {

    List<GeminiExpedienteChats> findGeminiExpedienteChats(Long nUnico, String codeTemplate);
}
