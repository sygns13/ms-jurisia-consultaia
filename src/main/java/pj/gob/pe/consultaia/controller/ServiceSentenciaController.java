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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pj.gob.pe.consultaia.service.business.SentenciaService;
import pj.gob.pe.consultaia.utils.beans.inputs.InputDescargaSentencia;
import pj.gob.pe.consultaia.utils.beans.inputs.InputGeneracionSentencia;
import pj.gob.pe.consultaia.utils.beans.inputs.InputListadoDemandasSentencias;
import pj.gob.pe.consultaia.utils.beans.responses.ApiResponse;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseGeneracionSentencia;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseGeneracionSentenciaDocx;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseListadoDemandaSentencia;

import java.util.List;
import java.util.concurrent.Callable;

@Tag(name = "Service Sentencia Controller", description = "API para generar la sentencia de una demanda con GEMINI")
@RestController
@RequestMapping("/v1/gemini")
@RequiredArgsConstructor
public class ServiceSentenciaController {

    private final SentenciaService sentenciaService;

    @Operation(summary = "Generación de sentencia de demanda con Gemini",
            description = "Recupera el PDF de la demanda por FTP y lo envía a Gemini para generar la sentencia " +
                    "(resuelve el fondo: FUNDADA / FUNDADA EN PARTE / INFUNDADA)")
    @PostMapping("/generar-sentencia")
    public Callable<ResponseEntity<ApiResponse<ResponseGeneracionSentencia>>> generarSentencia(
            @RequestHeader("SessionId") String SessionId,
            @Valid @RequestBody InputGeneracionSentencia input) {

        return () -> {
            long start = System.nanoTime();
            try {
                ResponseGeneracionSentencia result = sentenciaService.generarSentencia(input, SessionId);
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

    @Operation(summary = "Generación de sentencia de demanda con Gemini en formato DOCX",
            description = "Mismo flujo que /generar-sentencia, pero retorna el resultado como archivo .docx descargable")
    @PostMapping("/generar-sentencia-docx")
    public Callable<ResponseEntity<byte[]>> generarSentenciaDocx(
            @RequestHeader("SessionId") String SessionId,
            @Valid @RequestBody InputGeneracionSentencia input) {

        return () -> {
            ResponseGeneracionSentenciaDocx result = sentenciaService.generarSentenciaDocx(input, SessionId);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.getNombreArchivo() + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(result.getDocumento());
        };
    }

    @Operation(summary = "Listado paginado de sentencias de demanda generadas",
            description = "Lista las sentencias generadas del usuario de la sesión. Filtros opcionales en el body: " +
                    "rango de fechas (fechaInicial/fechaFinal, yyyy-MM-dd, comparadas contra fechaSend), año (exacto) " +
                    "y número de expediente (LIKE). Siempre se filtra por el userId de la sesión.")
    @PostMapping("/listar-demandas-sentencias")
    public ResponseEntity<ApiResponse<Page<ResponseListadoDemandaSentencia>>> listarDemandasSentencias(
            @RequestHeader("SessionId") String SessionId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestBody(required = false) InputListadoDemandasSentencias input) {

        long start = System.nanoTime();
        try {
            Pageable pageable = PageRequest.of(page, size).withSort(Sort.Direction.DESC, "fechaSend");
            Page<ResponseListadoDemandaSentencia> result =
                    sentenciaService.listarDemandasSentencias(input, pageable, SessionId);
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

    @Operation(summary = "Descarga de la sentencia de demanda generada en formato DOCX",
            description = "Recupera el registro de DemandasSentencias por su id (validando que pertenezca al usuario de " +
                    "la sesión) y devuelve el contenido del campo response como archivo .docx descargable, con el mismo " +
                    "formato que /generar-sentencia-docx")
    @PostMapping("/descargar-sentencia-docx")
    public ResponseEntity<byte[]> descargarSentenciaDocx(
            @RequestHeader("SessionId") String SessionId,
            @Valid @RequestBody InputDescargaSentencia input) throws Exception {

        ResponseGeneracionSentenciaDocx result = sentenciaService.descargarSentenciaDocx(input, SessionId);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.getNombreArchivo() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(result.getDocumento());
    }

    @Operation(summary = "Listado paginado de la última versión de cada sentencia generada (agrupado por nUnico)",
            description = "Igual que /listar-demandas-sentencias, pero agrupa por nUnico y devuelve solo la última " +
                    "versión (último registro, mayor id) de cada sentencia dentro del conjunto filtrado. Filtros opcionales " +
                    "en el body: rango de fechas (fechaInicial/fechaFinal yyyy-MM-dd vs fechaSend), año (exacto) y número " +
                    "de expediente (LIKE). Siempre se filtra por el userId de la sesión.")
    @PostMapping("/listar-demandas-sentencias-agrupadas")
    public ResponseEntity<ApiResponse<Page<ResponseListadoDemandaSentencia>>> listarDemandasSentenciasAgrupadas(
            @RequestHeader("SessionId") String SessionId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestBody(required = false) InputListadoDemandasSentencias input) {

        long start = System.nanoTime();
        try {
            Pageable pageable = PageRequest.of(page, size).withSort(Sort.Direction.DESC, "id");
            Page<ResponseListadoDemandaSentencia> result =
                    sentenciaService.listarUltimaVersionDemandasSentencias(input, pageable, SessionId);
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

    @Operation(summary = "Listado de todas las versiones de sentencias de una demanda por nUnico",
            description = "Lista todos los registros de DemandasSentencias de un nUnico, filtrando siempre por el " +
                    "userId de la sesión, ordenados del más nuevo al más antiguo (id DESC). Sin paginación.")
    @GetMapping("/listar-sentencias-por-nunico")
    public ResponseEntity<ApiResponse<List<ResponseListadoDemandaSentencia>>> listarSentenciasPorNunico(
            @RequestHeader("SessionId") String SessionId,
            @RequestParam(name = "nunico") Long nunico) {

        long start = System.nanoTime();
        try {
            List<ResponseListadoDemandaSentencia> result =
                    sentenciaService.listarSentenciasPorNunico(nunico, SessionId);
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
}
