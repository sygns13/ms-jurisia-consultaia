package pj.gob.pe.consultaia.model.beans.input;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Input MainRequest Model")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MainRequest {
    private String model;
    private List<Message> messages;
    private BigDecimal temperature;
}
