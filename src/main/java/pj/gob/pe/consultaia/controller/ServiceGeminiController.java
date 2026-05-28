package pj.gob.pe.consultaia.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Service Gemini Controller", description = "API para realizar peticiones a GEMINI")
@RestController
@RequestMapping("/v1/gemini")
@RequiredArgsConstructor
public class ServiceGeminiController {
/*
    @Operation(summary = "Generacion de Peticion a Gemini", description = "Generacion de Peticion a Gemini")
    @PostMapping("/consulta")
    public Callable<ResponseEntity<ApiResponse<String>>> interactuarMultimodal(
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @RequestParam("prompt") String prompt) throws IOException {

        Completions chatCompletionResponse = chatGPTService.processChatGPT(inputGeminiChat, SessionId);

        if(chatCompletionResponse == null) {
            throw new ModeloNotFoundException("Error de procesamiento de Datos. Comunicarse con un administrador ");
        }

        return new ResponseEntity<Completions>(chatCompletionResponse, HttpStatus.OK);
    }*/
}
