package pj.gob.pe.consultaia.service.business;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import pj.gob.pe.consultaia.model.entities.GeminiChats;
import pj.gob.pe.consultaia.utils.beans.inputs.InputDocument;
import pj.gob.pe.consultaia.utils.beans.inputs.InputGeminiChatFile;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseDocumentGemini;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseGeminiChat;

import java.util.List;

/**
 * Módulo conversacional con Gemini (espejo del flujo ChatGPT de {@link ChatGPTService}, pero
 * multimodal): historial por sessionUID (UUID), configuración desde BD (serviceCode
 * gemini_chat_1) y adjuntos por turno subidos a GCS y registrados en GeminiChatsFiles.
 */
public interface GeminiChatService {

    /**
     * Procesa un turno de conversación: valida sesión, recupera historial (texto + adjuntos
     * previos por URI gs://), sube los nuevos adjuntos al bucket del chat, invoca a Gemini y
     * persiste el turno con su respuesta.
     */
    ResponseGeminiChat processChat(String prompt, String sessionUID, List<InputGeminiChatFile> files, String SessionId) throws Exception;

    /** Lista paginada de conversaciones del usuario (primer turno de cada sessionUID). */
    Page<GeminiChats> listar(Pageable pageable, String buscar, String SessionId) throws Exception;

    /** Total de conversaciones (sessionUID distintos) del usuario. */
    Long getTotalConversaciones(String buscar, String SessionId) throws Exception;

    /** Conversación completa (turnos en orden cronológico, con sus adjuntos). */
    List<ResponseGeminiChat> getConversacion(String SessionId, String sessionUIDConversacion) throws Exception;

    /**
     * Procesamiento de documento por secciones con Gemini (corrección ortotipográfica), espejo
     * del flujo chat_gpt_2 de {@link ChatGPTService#processDocument}: cada sección con isSendIA=1
     * se envía a Gemini y el resultado se cachea en GeminiExpedienteChats por
     * (nUnico, templateCode, sectionId) con ventana de 7 días.
     */
    ResponseDocumentGemini processDocument(InputDocument inputDocument) throws Exception;
}
