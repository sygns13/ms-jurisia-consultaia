package pj.gob.pe.consultaia.utils.beans;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Párrafo de un documento por plantilla, partido en segmentos (uno por tramo de formato del Word).
 * La corrección debe devolver la misma cantidad de segmentos para no perder el formato.
 */
@Schema(description = "Párrafo de documento por plantilla, partido en segmentos de formato")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ParrafoPlantilla {

    @Schema(description = "Identificador del párrafo dentro de la generación")
    private Integer id;

    @Schema(description = "Segmentos de texto del párrafo, en orden")
    private List<String> segmentos;
}
