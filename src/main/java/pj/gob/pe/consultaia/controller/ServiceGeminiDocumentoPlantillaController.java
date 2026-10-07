package pj.gob.pe.consultaia.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pj.gob.pe.consultaia.service.business.GeminiDocumentoPlantillaService;
import pj.gob.pe.consultaia.utils.beans.inputs.InputDocumentoPlantilla;
import pj.gob.pe.consultaia.utils.beans.responses.ApiResponse;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseDocumentoPlantilla;

import java.util.concurrent.Callable;

/**
 * Procesamiento con Gemini de los documentos generados por plantilla completa en
 * ms-jurisia-judicial (configuración gemini_document_2). A diferencia de /v1/gemini-chat/documento
 * (una llamada por sección), aquí se hace UNA sola llamada por documento.
 */
@Tag(name = "Service Gemini Documento Plantilla Controller", description = "Corrección y redacción con Gemini de documentos generados por plantilla")
@RestController
@RequestMapping("/v1/gemini-documento")
@RequiredArgsConstructor
public class ServiceGeminiDocumentoPlantillaController {

    private final GeminiDocumentoPlantillaService geminiDocumentoPlantillaService;

    @Operation(summary = "Procesar documento por plantilla con Gemini",
            description = "En una sola llamada corrige la concordancia de los párrafos remitidos (devolviendo solo los corregidos, " +
                    "con la misma cantidad de segmentos) y redacta los bloques de origen IA. Cada llamada queda registrada en " +
                    "GeminiDocumentoPlantilla.")
    @PostMapping("/procesar")
    public Callable<ResponseEntity<ApiResponse<ResponseDocumentoPlantilla>>> procesar(
            @RequestHeader("SessionId") String SessionId,
            @RequestBody InputDocumentoPlantilla input) {

        return () -> {
            long start = System.nanoTime();
            try {
                ResponseDocumentoPlantilla result = geminiDocumentoPlantillaService.procesarDocumentoPlantilla(input, SessionId);
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
}
