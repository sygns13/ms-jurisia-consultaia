package pj.gob.pe.consultaia.utils.beans.inputs;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Input para descargar la sentencia generada de una demanda en formato DOCX")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class InputDescargaSentencia {

    @NotNull(message = "id es requerido")
    @Schema(description = "ID del registro de DemandasSentencias", example = "1")
    private Long id;
}
