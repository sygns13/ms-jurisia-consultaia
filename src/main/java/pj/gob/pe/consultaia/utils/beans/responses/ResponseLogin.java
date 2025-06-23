package pj.gob.pe.consultaia.utils.beans.responses;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Schema(description = "Response Login Model")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResponseLogin implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean success;
    private boolean itemFound;
    private String message;
    private UserLogin user;
}
