package pj.gob.pe.consultaia.utils.beans.responses;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pj.gob.pe.consultaia.model.entities.GeminiChats;
import pj.gob.pe.consultaia.model.entities.GeminiChatsFiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Turno del chat conversacional con Gemini (campos de {@link GeminiChats} aplanados) más sus
 * adjuntos registrados en GeminiChatsFiles. Es el objeto que devuelve la API y el que se publica
 * en Kafka (tópico judicial-metrics-gemini-chats), enriquecido con sedes/instancias, igual que en
 * el flujo de ChatGPT.
 */
@Schema(description = "Turno de conversación con Gemini, con sus adjuntos")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResponseGeminiChat {

    // ===== Campos de GeminiChats =====
    private Long id;
    private Long userId;
    private String model;
    private String roleSystem;
    private String prompt;
    private BigDecimal temperature;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime fechaSend;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime fechaResponse;

    private String response;
    private Double timeSeconds;
    private String sessionUID;
    private Integer status;
    private Integer hasFiles;
    private Integer configurationsId;

    // ===== Adjuntos del turno (nombre original + URI gs://) =====
    private List<GeminiChatsFiles> files;

    // ===== Sedes/instancias del usuario (para métricas, igual que ChatGPT) =====
    private List<Sedes> sedes;

    /** Aplana la entidad y adjunta sus archivos. */
    public static ResponseGeminiChat from(GeminiChats chat, List<GeminiChatsFiles> files) {
        ResponseGeminiChat r = new ResponseGeminiChat();
        r.setId(chat.getId());
        r.setUserId(chat.getUserId());
        r.setModel(chat.getModel());
        r.setRoleSystem(chat.getRoleSystem());
        r.setPrompt(chat.getPrompt());
        r.setTemperature(chat.getTemperature());
        r.setFechaSend(chat.getFechaSend());
        r.setFechaResponse(chat.getFechaResponse());
        r.setResponse(chat.getResponse());
        r.setTimeSeconds(chat.getTimeSeconds());
        r.setSessionUID(chat.getSessionUID());
        r.setStatus(chat.getStatus());
        r.setHasFiles(chat.getHasFiles());
        r.setConfigurationsId(chat.getConfigurations() != null ? chat.getConfigurations().getId() : chat.getConfigurationsId());
        r.setFiles(files);
        return r;
    }
}
