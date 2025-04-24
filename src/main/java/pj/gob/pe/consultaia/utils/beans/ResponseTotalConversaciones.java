package pj.gob.pe.consultaia.utils.beans;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Response Total Conversaciones Model")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResponseTotalConversaciones {

    Long totalConversaciones;
}
