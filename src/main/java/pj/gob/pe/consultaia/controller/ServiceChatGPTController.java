package pj.gob.pe.consultaia.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pj.gob.pe.consultaia.exception.ModeloNotFoundException;
import pj.gob.pe.consultaia.model.beans.output.ChatCompletionResponse;
import pj.gob.pe.consultaia.model.entities.Completions;
import pj.gob.pe.consultaia.service.business.ChatGPTService;
import pj.gob.pe.consultaia.utils.beans.InputChatGPT;


@Tag(name = "Service ChatGPT Controller", description = "API para realizar peticiones a CHAT GPT")
@RestController
@RequestMapping("/v1/chatgpt")
@RequiredArgsConstructor
public class ServiceChatGPTController {

    private final ChatGPTService chatGPTService;

    @Operation(summary = "Generacion de Peticion a ChatGPT", description = "Generacion de Peticion a ChatGPT")
    @PostMapping("/consulta")
    public ResponseEntity<Completions> registrar(
            @RequestHeader("SessionId") String SessionId,
            @Valid @RequestBody InputChatGPT inputChatGPT) throws Exception{

        Completions chatCompletionResponse = chatGPTService.processChatGPT(inputChatGPT, SessionId);

        if(chatCompletionResponse == null) {
            throw new ModeloNotFoundException("Error de procesamiento de Datos. Comunicarse con un administrador ");
        }

        return new ResponseEntity<Completions>(chatCompletionResponse, HttpStatus.OK);
    }
}
