package pj.gob.pe.consultaia.service.business;


import pj.gob.pe.consultaia.model.entities.Completions;
import pj.gob.pe.consultaia.utils.beans.InputChatGPT;

public interface ChatGPTService {

    Completions processChatGPT (InputChatGPT inputChatGPT, String SessionId) throws Exception;
}
