package pj.gob.pe.consultaia.dao.mysql;

import pj.gob.pe.consultaia.model.entities.GeminiChatsFiles;

import java.util.Collection;
import java.util.List;

public interface GeminiChatsFilesDAO extends GenericDAO<GeminiChatsFiles, Long> {

    List<GeminiChatsFiles> listarPorChatIds(Collection<Long> geminiChatIds, Integer status);

    List<GeminiChatsFiles> listarPorSessionUID(String sessionUID, Integer status);
}
