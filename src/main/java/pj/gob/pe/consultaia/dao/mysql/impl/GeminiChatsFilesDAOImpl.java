package pj.gob.pe.consultaia.dao.mysql.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import pj.gob.pe.consultaia.dao.mysql.GeminiChatsFilesDAO;
import pj.gob.pe.consultaia.model.entities.GeminiChatsFiles;
import pj.gob.pe.consultaia.repository.GeminiChatsFilesRepo;
import pj.gob.pe.consultaia.repository.GenericRepo;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class GeminiChatsFilesDAOImpl extends GenericDAOImpl<GeminiChatsFiles, Long> implements GeminiChatsFilesDAO {

    private final GeminiChatsFilesRepo repo;

    @Override
    protected GenericRepo<GeminiChatsFiles, Long> getRepo() {
        return repo;
    }

    @Override
    public List<GeminiChatsFiles> listarPorChatIds(Collection<Long> geminiChatIds, Integer status) {
        if (geminiChatIds == null || geminiChatIds.isEmpty()) {
            return Collections.emptyList();
        }
        return repo.findByGeminiChatIdInAndStatusOrderByIdAsc(geminiChatIds, status);
    }

    @Override
    public List<GeminiChatsFiles> listarPorSessionUID(String sessionUID, Integer status) {
        return repo.findBySessionUIDAndStatusOrderByIdAsc(sessionUID, status);
    }
}
