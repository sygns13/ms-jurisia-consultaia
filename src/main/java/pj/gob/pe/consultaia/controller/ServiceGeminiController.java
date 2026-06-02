package pj.gob.pe.consultaia.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pj.gob.pe.consultaia.service.business.GeminiService;
import pj.gob.pe.consultaia.utils.beans.inputs.InputCalificacionDemanda;
import pj.gob.pe.consultaia.utils.beans.inputs.InputDescargaCalificacion;
import pj.gob.pe.consultaia.utils.beans.inputs.InputListadoDemandasCalificadas;
import pj.gob.pe.consultaia.utils.beans.responses.ApiResponse;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseCalificacionDemanda;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseCalificacionDemandaDocx;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseListadoDemandaCalificada;

import java.util.concurrent.Callable;

@Tag(name = "Service Gemini Controller", description = "API para realizar peticiones a GEMINI")
@RestController
@RequestMapping("/v1/gemini")
@RequiredArgsConstructor
public class ServiceGeminiController {

    private final GeminiService geminiService;

    @Operation(summary = "Calificación de demanda con Gemini",
            description = "Recupera el PDF de la demanda por FTP y lo envía a Gemini para generar la calificación (Auto Admisorio / Inadmisibilidad / Improcedencia)")
    @PostMapping("/calificar-demanda")
    public Callable<ResponseEntity<ApiResponse<ResponseCalificacionDemanda>>> calificarDemanda(
            @RequestHeader("SessionId") String SessionId,
            @Valid @RequestBody InputCalificacionDemanda input) {

        return () -> {
            long start = System.nanoTime();
            try {
                ResponseCalificacionDemanda result = geminiService.calificarDemanda(input, SessionId);
                double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                return ResponseEntity.ok(ApiResponse.ok(result, seconds));
            } catch (Exception e) {
                Throwable cause = e;
                while (cause.getCause() != null) cause = cause.getCause();
                double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                return ResponseEntity.internalServerError()
                        .body(ApiResponse.error(cause.getMessage(), seconds));
            }
        };
    }

    @Operation(summary = "Calificación de demanda con Gemini en formato DOCX",
            description = "Mismo flujo que /calificar-demanda, pero retorna el resultado como archivo .docx descargable")
    @PostMapping("/calificar-demanda-docx")
    public Callable<ResponseEntity<byte[]>> calificarDemandaDocx(
            @RequestHeader("SessionId") String SessionId,
            @Valid @RequestBody InputCalificacionDemanda input) {

        return () -> {
            ResponseCalificacionDemandaDocx result = geminiService.calificarDemandaDocx(input, SessionId);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.getNombreArchivo() + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(result.getDocumento());
        };
    }

    @Operation(summary = "Listado paginado de demandas calificadas",
            description = "Lista las demandas calificadas del usuario de la sesión. Filtros opcionales en el body: " +
                    "rango de fechas (fechaInicial/fechaFinal, yyyy-MM-dd, comparadas contra fechaSend), año (exacto) " +
                    "y número de expediente (LIKE). Siempre se filtra por el userId de la sesión.")
    @PostMapping("/listar-demandas-calificadas")
    public ResponseEntity<ApiResponse<Page<ResponseListadoDemandaCalificada>>> listarDemandasCalificadas(
            @RequestHeader("SessionId") String SessionId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestBody(required = false) InputListadoDemandasCalificadas input) {

        long start = System.nanoTime();
        try {
            Pageable pageable = PageRequest.of(page, size).withSort(Sort.Direction.DESC, "fechaSend");
            Page<ResponseListadoDemandaCalificada> result =
                    geminiService.listarDemandasCalificadas(input, pageable, SessionId);
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            return ResponseEntity.ok(ApiResponse.ok(result, seconds));
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(cause.getMessage(), seconds));
        }
    }

    @Operation(summary = "Descarga de la calificación de demanda en formato DOCX",
            description = "Recupera el registro de DemandasCalificadas por su id (validando que pertenezca al usuario de " +
                    "la sesión) y devuelve el contenido del campo response como archivo .docx descargable, con el mismo " +
                    "formato que /calificar-demanda-docx")
    @PostMapping("/descargar-calificacion-docx")
    public ResponseEntity<byte[]> descargarCalificacionDocx(
            @RequestHeader("SessionId") String SessionId,
            @Valid @RequestBody InputDescargaCalificacion input) throws Exception {

        ResponseCalificacionDemandaDocx result = geminiService.descargarCalificacionDocx(input, SessionId);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.getNombreArchivo() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(result.getDocumento());
    }
}
