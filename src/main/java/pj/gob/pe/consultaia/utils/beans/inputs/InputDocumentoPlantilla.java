package pj.gob.pe.consultaia.utils.beans.inputs;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pj.gob.pe.consultaia.utils.beans.BloquePlantilla;
import pj.gob.pe.consultaia.utils.beans.ParrafoPlantilla;

import java.util.List;

/** Solicitud de ms-jurisia-judicial para procesar un documento por plantilla en una sola llamada a Gemini. */
@Schema(description = "Input de procesamiento de documento por plantilla con Gemini")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class InputDocumentoPlantilla {

    @Schema(description = "UUID de la generación asignado por ms-jurisia-judicial")
    private String sessionUID;

    @Schema(description = "ID de Expediente")
    private Long nUnico;

    @Schema(description = "ID de Usuario")
    private Long idUser;

    private Long idDocumento;
    private String documento;
    private Long idPlantilla;
    private String codigoPlantilla;
    private Integer versionPlantilla;

    @Schema(description = "Resumen del expediente para redactar los bloques")
    private String contextoExpediente;

    @Schema(description = "Párrafos a corregir")
    private List<ParrafoPlantilla> parrafos;

    @Schema(description = "Bloques a redactar")
    private List<BloquePlantilla> bloques;
}
