package pj.gob.pe.consultaia.model.entities;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "Configurations Model")
@Entity
@Table(name = "Configurations")
@Data // Lombok: Genera getters, setters, toString, equals, y hashCode
@NoArgsConstructor // Lombok: Constructor sin argumentos
@AllArgsConstructor // Lombok: Constructor con todos los argumentos
public class Configurations {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "ID único de la configuración", example = "1")
    private Integer id;

    @Column(name = "serviceCode", length = 50)
    @Schema(description = "Código del servicio", example = "SVC001")
    private String serviceCode;

    @Column(name = "model", length = 50)
    @Schema(description = "Model de GPT", example = "gpt-4o")
    private String model;

    @Column(name = "descripcion", columnDefinition = "TEXT")
    @Schema(description = "Descripción de la configuración", example = "Configuración inicial")
    private String descripcion;

    @Column(name = "roleSystem", columnDefinition = "TEXT")
    @Schema(description = "Rol del sistema", example = "Admin")
    private String roleSystem;

    @Column(name = "promptDefault", columnDefinition = "TEXT")
    @Schema(description = "Prompt por defecto", example = "Bienvenido")
    private String promptDefault;

    @Column(name = "maxMessages")
    @Schema(description = "Número máximo de mensajes permitidos", example = "100")
    private Integer maxMessages;

    @Column(name = "temperature", precision = 2, scale = 1)
    @DecimalMin(value = "0.0", message = "El valor debe ser mayor o igual a 0.0")
    @DecimalMax(value = "1.0", message = "El valor debe ser menor o igual a 1.0")
    private BigDecimal temperature;

    @Schema(description = "Estado del Registro")
    @Column(name="activo", nullable = true)
    private Integer activo;

    @Schema(description = "Borrado Lógico del Registro")
    @Column(name="borrado", nullable = true)
    private Integer borrado;

    @Schema(description = "Fecha de Creación del Registro")
    @JsonFormat(pattern="yyyy-MM-dd")
    @Column(name="regDate", nullable = true)
    private LocalDate regDate;

    @Schema(description = "Fecha y Hora de Creación del Registro")
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Column(name="regDatetime", nullable = true)
    private LocalDateTime regDatetime;

    @Schema(description = "Epoch de Creación del Registro")
    @Column(name="regTimestamp", nullable = true)
    private Long regTimestamp;

    @Schema(description = "Usuario que insertó el registro")
    @Column(name="regUserId", nullable = true)
    private Long regUserId;

    @Schema(description = "Fecha de Edición del Registro")
    @JsonFormat(pattern="yyyy-MM-dd")
    @Column(name="updDate", nullable = true)
    private LocalDate updDate;

    @Schema(description = "Fecha y Hora de Edición del Registro")
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Column(name="updDatetime", nullable = true)
    private LocalDateTime updDatetime;

    @Schema(description = "Epoch de Edición del Registro")
    @Column(name="updTimestamp", nullable = true)
    private Long updTimestamp;

    @Schema(description = "Usuario que editó el registro")
    @Column(name="updUserId", nullable = true)
    private Long updUserId;
}
