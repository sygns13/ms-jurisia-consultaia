package pj.gob.pe.consultaia.repository;

import pj.gob.pe.consultaia.model.entities.GeminiChatsFiles;

import java.util.Collection;
import java.util.List;

public interface GeminiChatsFilesRepo extends GenericRepo<GeminiChatsFiles, Long> {

    List<GeminiChatsFiles> findByGeminiChatIdInAndStatusOrderByIdAsc(Collection<Long> geminiChatIds, Integer status);

    List<GeminiChatsFiles> findBySessionUIDAndStatusOrderByIdAsc(String sessionUID, Integer status);
}
