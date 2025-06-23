package pj.gob.pe.consultaia.utils.beans.inputs;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pj.gob.pe.consultaia.utils.beans.SectionTemplate;

import java.util.List;

@Schema(description = "Input Document to Formatt and Send to IA")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class InputDocument {

    @Schema(description = "ID de Expediente")
    private Long nUnico;

    @Schema(description = "Template Code")
    private String codeTemplate;

    @Schema(description = "Section Templates")
    private List<SectionTemplate> sectionTemplates;

    @Schema(description = "ID de Usuario")
    private Long idUser;
}
