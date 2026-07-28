package pj.gob.pe.consultaia.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import pj.gob.pe.consultaia.model.entities.GeminiChats;
import pj.gob.pe.consultaia.service.business.GeminiChatService;
import pj.gob.pe.consultaia.utils.beans.inputs.InputDocument;
import pj.gob.pe.consultaia.utils.beans.inputs.InputGeminiChatFile;
import pj.gob.pe.consultaia.utils.beans.responses.ApiResponse;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseDocumentGemini;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseGeminiChat;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseTotalConversaciones;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Módulo conversacional con Gemini: espejo funcional del flujo ChatGPT
 * ({@link ServiceChatGPTController}) con soporte de archivos adjuntos por turno
 * (multipart/form-data). Las conversaciones se identifican por sessionUID (UUID) y la sesión de
 * usuario viaja en el header SessionId, igual que en el resto del microservicio.
 */
@Tag(name = "Service Gemini Chat Controller", description = "API conversacional (chat) con GEMINI, con soporte de archivos adjuntos")
@RestController
@RequestMapping("/v1/gemini-chat")
@RequiredArgsConstructor
public class ServiceGeminiChatController {

    private final GeminiChatService geminiChatService;

    @Operation(summary = "Consulta conversacional a Gemini (con adjuntos opcionales)",
            description = "Procesa un turno de conversación con Gemini. Recibe multipart/form-data con el prompt, " +
                    "el sessionUID de la conversación (vacío para iniciar una nueva: se genera un UUID) y archivos " +
                    "adjuntos opcionales (PDF, imágenes, audio, video, Word .doc/.docx, texto). Los adjuntos se " +
                    "suben al bucket GCS del chat, se registran en GeminiChatsFiles y se reinyectan como contexto " +
                    "en los turnos siguientes de la conversación.")
    @PostMapping(value = "/consulta", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Callable<ResponseEntity<ApiResponse<ResponseGeminiChat>>> consulta(
            @RequestHeader("SessionId") String SessionId,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "sessionUID", required = false) String sessionUID,
            @RequestParam(value = "files", required = false) MultipartFile[] files) throws Exception {

        // Los bytes del multipart se leen ANTES de entrar al Callable async, para no depender
        // del ciclo de vida de los archivos temporales de multipart (mismo criterio que el
        // chat multimodal de prueba).
        List<InputGeminiChatFile> adjuntos = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                if (file != null && !file.isEmpty()) {
                    adjuntos.add(new InputGeminiChatFile(
                            file.getOriginalFilename(), file.getContentType(), file.getBytes()));
                }
            }
        }

        return () -> {
            long start = System.nanoTime();
            try {
                ResponseGeminiChat result = geminiChatService.processChat(prompt, sessionUID, adjuntos, SessionId);
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

    @Operation(summary = "Listado paginado de conversaciones con Gemini",
            description = "Lista las conversaciones del usuario de la sesión (primer turno de cada sessionUID), " +
                    "igual que el listado del flujo ChatGPT")
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<Page<GeminiChats>>> list(
            @RequestHeader("SessionId") String SessionId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {

        long start = System.nanoTime();
        try {
            Pageable pageable = PageRequest.of(page, size).withSort(Sort.Direction.DESC, "id");
            Page<GeminiChats> result = geminiChatService.listar(pageable, "", SessionId);
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

    @Operation(summary = "Total de conversaciones con Gemini",
            description = "Total de conversaciones (sessionUID distintos) del usuario de la sesión")
    @GetMapping("/gettotaloperaciones")
    public ResponseEntity<ApiResponse<ResponseTotalConversaciones>> getTotalOperaciones(
            @RequestHeader("SessionId") String SessionId) {

        long start = System.nanoTime();
        try {
            Long totalConversaciones = geminiChatService.getTotalConversaciones("", SessionId);

            ResponseTotalConversaciones responseTotalConversaciones = new ResponseTotalConversaciones();
            responseTotalConversaciones.setTotalConversaciones(totalConversaciones);

            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            return ResponseEntity.ok(ApiResponse.ok(responseTotalConversaciones, seconds));
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(cause.getMessage(), seconds));
        }
    }

    @Operation(summary = "Procesamiento de documento por secciones con Gemini",
            description = "Espejo del flujo /v1/chatgpt/documento pero con Gemini (configuración gemini_document_1, " +
                    "corrección ortotipográfica). Cada sección con isSendIA=1 se envía a Gemini; el resultado se " +
                    "cachea en GeminiExpedienteChats por nUnico + templateCode + sectionId (ventana de 7 días), de " +
                    "modo que las secciones ya corregidas no se reprocesan.")
    @PostMapping("/documento")
    public Callable<ResponseEntity<ApiResponse<ResponseDocumentGemini>>> processDocument(
            @Valid @RequestBody InputDocument inputDocument) {

        return () -> {
            long start = System.nanoTime();
            try {
                ResponseDocumentGemini result = geminiChatService.processDocument(inputDocument);
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

    @Operation(summary = "Conversación completa con Gemini",
            description = "Devuelve los turnos de una conversación (sessionUID) en orden cronológico, " +
                    "incluyendo los adjuntos de cada turno (nombre original y URI gs://)")
    @GetMapping("/conversacion")
    public ResponseEntity<ApiResponse<List<ResponseGeminiChat>>> conversacion(
            @RequestHeader("SessionId") String SessionId,
            @RequestParam(name = "sessionuid", defaultValue = "0") String SessionUID) {

        long start = System.nanoTime();
        try {
            List<ResponseGeminiChat> result = geminiChatService.getConversacion(SessionId, SessionUID);
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
