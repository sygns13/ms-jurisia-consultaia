package pj.gob.pe.consultaia.utils.beans;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Input Chat GPT")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class InputChatGPT {

    @JsonProperty("sessionUID")
    private String sessionUID;

    @Schema(description = "Username del Usuario")
    @NotNull( message = "{input.prompt.notnull}")
    @Size(min = 1, max = 1000, message = "{input.prompt.size}")
    private String prompt;

}
