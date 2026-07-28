package pj.gob.pe.consultaia.model.entities;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Adjunto de un turno del chat conversacional con Gemini. Registra el nombre original del
 * archivo y su dirección en GCS (URI gs:// en el bucket de adjuntos del chat). Para Word
 * (.doc/.docx) se guarda además el texto extraído con POI, que es lo que se envía a Gemini
 * (no acepta Word nativo) y permite reinyectar el documento en turnos posteriores.
 */
@Schema(description = "Entidad que representa la tabla GeminiChatsFiles")
@Entity
@Table(name = "GeminiChatsFiles")
@Data // Lombok: Genera getters, setters, toString, equals, y hashCode
@NoArgsConstructor // Lombok: Constructor sin argumentos
@AllArgsConstructor // Lombok: Constructor con todos los argumentos
public class GeminiChatsFiles {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "ID único del adjunto", example = "1")
    private Long id;

    @Column(name = "geminiChatId", nullable = false)
    @Schema(description = "ID del chat (GeminiChats) al que pertenece el adjunto", example = "10")
    private Long geminiChatId;

    @Column(name = "sessionUID", length = 50, nullable = false)
    @Schema(description = "UUID de la conversación (mismo que GeminiChats.sessionUID)", example = "session-12345")
    private String sessionUID;

    @Column(name = "fileName", length = 255)
    @Schema(description = "Nombre original del archivo adjunto", example = "demanda_alimentos.pdf")
    private String fileName;

    @Column(name = "mimeType", length = 150)
    @Schema(description = "MIME type del archivo", example = "application/pdf")
    private String mimeType;

    @Column(name = "sizeBytes")
    @Schema(description = "Tamaño del archivo en bytes", example = "204800")
    private Long sizeBytes;

    @Column(name = "gcsUri", length = 500)
    @Schema(description = "Dirección del archivo en GCS", example = "gs://pj_gemini_chat_adjuntos/chats/uuid/archivo.pdf")
    private String gcsUri;

    @JsonIgnore // uso interno (reinyección al historial); no se expone en respuestas ni Kafka
    @Column(name = "textoExtraido", columnDefinition = "LONGTEXT")
    @Schema(description = "Texto extraído con POI para Word (.doc/.docx); null para formatos nativos de Gemini")
    private String textoExtraido;

    @Column(name = "fechaReg")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "Fecha y hora de registro del adjunto", example = "2026-07-28T12:00:00")
    private LocalDateTime fechaReg;

    @Column(name = "status")
    @Schema(description = "Status del adjunto (1 = activo)", example = "1")
    private Integer status;
}
