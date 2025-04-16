package pj.gob.pe.consultaia.model.beans.output;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pj.gob.pe.consultaia.model.entities.Completions;
import pj.gob.pe.consultaia.model.entities.Configurations;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data // Lombok: Genera getters, setters, toString, equals, y hashCode
@NoArgsConstructor // Lombok: Constructor sin argumentos
@AllArgsConstructor // Lombok: Constructor con todos los argumentos
public class CompletionsResponse {

    private Long id;
    private Long userId;
    private String model;
    private String roleSystem;
    private String roleUser;
    private BigDecimal temperature;

    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    private LocalDateTime fechaSend;

    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    private LocalDateTime fechaResponse;

    private String idGpt;
    private String object;
    private Long created;
    private String modelResponse;
    private String roleResponse;
    private String roleContent;
    private String refusal;
    private Integer logprobs;
    private String finishReason;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer cachedTokens;
    private Integer audioTokens;
    private Integer completionReasoningTokens;
    private Integer completionAudioTokens;
    private Integer completionAceptedTokens;
    private Integer completionRejectedTokens;
    private String serviceTier;
    private String systemFingerprint;
    private String sessionUID;
    private Integer status;

    public CompletionsResponse(Completions completions){
        this.id = completions.getId();
        this.object = completions.getObject();
        this.created = completions.getCreated();
        this.modelResponse = completions.getModelResponse();
        this.roleResponse = completions.getRoleResponse();
        this.roleContent = completions.getRoleContent();
        this.refusal = completions.getRefusal();
        this.logprobs = completions.getLogprobs();
        this.finishReason = completions.getFinishReason();
        this.promptTokens = completions.getPromptTokens();
        this.completionTokens = completions.getCompletionTokens();
        this.totalTokens = completions.getTotalTokens();
        this.cachedTokens = completions.getCachedTokens();
        this.audioTokens = completions.getAudioTokens();
        this.completionReasoningTokens = completions.getCompletionReasoningTokens();
        this.completionAudioTokens = completions.getCompletionAudioTokens();
        this.completionAceptedTokens = completions.getCompletionAceptedTokens();
        this.completionRejectedTokens = completions.getCompletionRejectedTokens();
        this.serviceTier = completions.getServiceTier();
        this.systemFingerprint = completions.getSystemFingerprint();
        this.sessionUID = completions.getSessionUID();
        this.status = completions.getStatus();
    }
}
