package pj.gob.pe.consultaia.service.business;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import pj.gob.pe.consultaia.model.entities.Completions;
import pj.gob.pe.consultaia.utils.beans.InputChatGPT;

import java.util.List;

public interface ChatGPTService {

    Completions processChatGPT (InputChatGPT inputChatGPT, String SessionId) throws Exception;

    Page<Completions> listar(Pageable pageable, String buscar, String SessionId) throws Exception;

    List<Completions> getConversacion(String SessionId, String sessionUIDConversacion) throws Exception;
}
