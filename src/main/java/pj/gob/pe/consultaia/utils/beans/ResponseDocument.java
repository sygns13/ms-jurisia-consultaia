package pj.gob.pe.consultaia.utils.beans;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Response Document to Formatted")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResponseDocument {

    @Schema(description = "ID de Expediente")
    private Long nUnico;

    @Schema(description = "Template Code")
    private String codeTemplate;

    @Schema(description = "Section Templates")
    private List<SectionTemplate> sectionTemplates;

    private String model;
    private String roleSystem;
    private BigDecimal temperature;
    private String object;
    private String modelResponse;
    private String roleResponse;
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
    private Integer configurationsId;
    private String sessionUID;
}
