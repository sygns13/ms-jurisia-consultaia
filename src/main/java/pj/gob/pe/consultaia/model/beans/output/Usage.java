package pj.gob.pe.consultaia.model.beans.output;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Output Usage Model")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Usage {
    @JsonProperty("prompt_tokens")
    private Integer promptTokens;

    @JsonProperty("completion_tokens")
    private Integer completionTokens;

    @JsonProperty("total_tokens")
    private Integer totalTokens;

    @JsonProperty("prompt_tokens_details")
    private TokensDetails promptTokensDetails;

    @JsonProperty("completion_tokens_details")
    private TokensDetails completionTokensDetails;
}
