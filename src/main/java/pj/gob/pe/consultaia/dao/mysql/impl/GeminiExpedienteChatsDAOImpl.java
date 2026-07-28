package pj.gob.pe.consultaia.dao.mysql.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import pj.gob.pe.consultaia.dao.mysql.GeminiExpedienteChatsDAO;
import pj.gob.pe.consultaia.model.entities.GeminiExpedienteChats;
import pj.gob.pe.consultaia.repository.GeminiExpedienteChatsRepo;
import pj.gob.pe.consultaia.repository.GenericRepo;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class GeminiExpedienteChatsDAOImpl extends GenericDAOImpl<GeminiExpedienteChats, Long> implements GeminiExpedienteChatsDAO {

    private final GeminiExpedienteChatsRepo repo;

    @Override
    protected GenericRepo<GeminiExpedienteChats, Long> getRepo() {
        return repo;
    }

    @Override
    public List<GeminiExpedienteChats> findGeminiExpedienteChats(Long nUnico, String codeTemplate) {
        return repo.findGeminiExpedienteChats(nUnico, codeTemplate);
    }
}
