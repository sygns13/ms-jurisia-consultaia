package pj.gob.pe.consultaia.utils.beans.responses;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Schema(description = "Data de Instancias SIJ Model")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DataInstanciaDTO implements Serializable {

    private static final long serialVersionUID = 1L;


    private String codigoInstancia;
    private String codigoDistrito;
    private String codigoProvincia;
    private String codigoOrganoJurisdiccional;
    private String instancia;
    private Long nInstancia;
    private String ubicacion;
    private String sigla;
    private String codigoSede;
    private String codigoUbigeo;
    private String indicadorBaja;
}
