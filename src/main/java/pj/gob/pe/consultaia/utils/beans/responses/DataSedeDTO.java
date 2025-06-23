package pj.gob.pe.consultaia.utils.beans.responses;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Schema(description = "Data de Sedes SIJ Model")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DataSedeDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String codigoSede;
    private String sede;
    private String activo;
    private String codigoDistrito;
    private String direccion;
}
