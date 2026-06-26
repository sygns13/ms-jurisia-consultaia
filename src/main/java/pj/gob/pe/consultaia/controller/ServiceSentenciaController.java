package pj.gob.pe.consultaia.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pj.gob.pe.consultaia.service.business.SentenciaService;
import pj.gob.pe.consultaia.utils.beans.inputs.InputGeneracionSentencia;
import pj.gob.pe.consultaia.utils.beans.responses.ApiResponse;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseGeneracionSentencia;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseGeneracionSentenciaDocx;

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
}
