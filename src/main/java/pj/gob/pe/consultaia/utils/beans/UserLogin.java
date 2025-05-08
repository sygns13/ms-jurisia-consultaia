package pj.gob.pe.consultaia.utils.beans;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.redis.core.RedisHash;

import java.io.Serializable;

@Schema(description = "UserLogin Model")
@RedisHash("UserLogin")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserLogin implements Serializable {

    private static final long serialVersionUID = 1L;

    private String userSessionsId;
    private Long idUser;
    private Integer tipoDocumento;
    private String documento;
    private String apellidos;
    private String nombres;
    private String username;
    private String email;
    private Integer genero;
    private String telefono;
    private String direccion;
    private Integer activo;
    private Long idDependencia;
    private String nombreDependencia;
    private String codigoDependencia;
    private String siglaDependencia;
    private String cargo;
    private Long idTipoUser;
    private String tipoUser;
    private TokenResponse token;
}
