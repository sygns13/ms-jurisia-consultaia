package pj.gob.pe.consultaia.utils.beans.inputs;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Schema(description = "Filtros opcionales para el listado de sentencias de demanda generadas. El userId se obtiene de la sesión.")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class InputListadoDemandasSentencias {

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Fecha inicial (inclusive) a comparar contra fechaSend. Formato yyyy-MM-dd", example = "2026-01-01")
    private LocalDate fechaInicial;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Fecha final (inclusive) a comparar contra fechaSend. Formato yyyy-MM-dd", example = "2026-12-31")
    private LocalDate fechaFinal;

    @Schema(description = "Año del expediente (filtro exacto)", example = "2026")
    private String anio;

    @Schema(description = "Número de expediente (filtro tipo LIKE)", example = "00272")
    private String expNro;
}
