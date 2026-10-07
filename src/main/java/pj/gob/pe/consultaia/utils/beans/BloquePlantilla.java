package pj.gob.pe.consultaia.utils.beans;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Bloque de texto que la IA redacta para una variable de origen IA")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BloquePlantilla {

    @Schema(description = "Nombre de la variable de la plantilla")
    private String variable;

    @Schema(description = "Instrucción de redacción (en la solicitud)")
    private String instruccion;

    @Schema(description = "Texto redactado (en la respuesta)")
    private String contenido;
}
