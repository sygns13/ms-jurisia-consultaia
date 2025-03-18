package pj.gob.pe.consultaia.model.beans.output;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Output TokensDetails Model")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TokensDetails {

    @JsonProperty("cached_tokens")
    private Integer cachedTokens;

    @JsonProperty("audio_tokens")
    private Integer audioTokens;

    @JsonProperty("reasoning_tokens")
    private Integer reasoningTokens; // Usamos Integer para permitir null

    @JsonProperty("accepted_prediction_tokens")
    private Integer acceptedPredictionTokens; // Usamos Integer para permitir null

    @JsonProperty("rejected_prediction_tokens")
    private Integer rejectedPredictionTokens; // Usamos Integer para permitir null
}
