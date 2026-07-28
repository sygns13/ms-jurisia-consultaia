package pj.gob.pe.consultaia.dao.mysql;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import pj.gob.pe.consultaia.model.entities.GeminiChats;

import java.util.List;
import java.util.Map;

public interface GeminiChatsDAO extends GenericDAO<GeminiChats, Long> {

    List<GeminiChats> getGeminiChatsByFilters(Map<String, Object> filters,
                                              Map<String, Object> notEqualFilters,
                                              Integer limit,
                                              String orderByField);

    Page<GeminiChats> getGralGeminiChatsByFilters(
            Map<String, Object> filters,
            Map<String, Object> notEqualFilters,
            Pageable pageable);

    Long getTotalConversaciones(
            Map<String, Object> filters,
            Map<String, Object> notEqualFilters);
}
