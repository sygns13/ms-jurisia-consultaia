package pj.gob.pe.consultaia.dao.mysql.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import pj.gob.pe.consultaia.dao.mysql.GeminiChatsDAO;
import pj.gob.pe.consultaia.model.entities.GeminiChats;
import pj.gob.pe.consultaia.repository.GeminiChatsRepo;
import pj.gob.pe.consultaia.repository.GenericRepo;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class GeminiChatsDAOImpl extends GenericDAOImpl<GeminiChats, Long> implements GeminiChatsDAO {

    private final GeminiChatsRepo repo;

    @Override
    protected GenericRepo<GeminiChats, Long> getRepo() {
        return repo;
    }

    @Override
    public List<GeminiChats> getGeminiChatsByFilters(Map<String, Object> filters, Map<String, Object> notEqualFilters, Integer limit, String orderByField) {
        return repo.getGeminiChatsByFilters(filters, notEqualFilters, limit, orderByField);
    }

    @Override
    public Page<GeminiChats> getGralGeminiChatsByFilters(Map<String, Object> filters, Map<String, Object> notEqualFilters, Pageable pageable) {
        return repo.getGralGeminiChatsByFilters(filters, notEqualFilters, pageable);
    }

    @Override
    public Long getTotalConversaciones(Map<String, Object> filters, Map<String, Object> notEqualFilters) {
        return repo.getTotalConversaciones(filters, notEqualFilters);
    }
}
