package pj.gob.pe.consultaia.model.beans.output;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Output Choice Model")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Choice {

    private Integer index;
    private Message message;
    private Integer logprobs;

    @JsonProperty("finish_reason")
    private String finishReason;
}
