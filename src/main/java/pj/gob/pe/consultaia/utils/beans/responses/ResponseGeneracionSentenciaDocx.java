package pj.gob.pe.consultaia.utils.beans.responses;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Response de generación de sentencia de demanda en formato DOCX")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResponseGeneracionSentenciaDocx {

    @Schema(description = "Contenido binario del documento DOCX")
    private byte[] documento;

    @Schema(description = "Nombre sugerido del archivo a descargar")
    private String nombreArchivo;
}
