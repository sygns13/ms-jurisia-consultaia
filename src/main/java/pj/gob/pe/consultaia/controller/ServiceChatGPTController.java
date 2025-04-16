package pj.gob.pe.consultaia.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pj.gob.pe.consultaia.exception.ModeloNotFoundException;
import pj.gob.pe.consultaia.model.beans.output.ChatCompletionResponse;
import pj.gob.pe.consultaia.model.beans.output.CompletionsResponse;
import pj.gob.pe.consultaia.model.entities.Completions;
import pj.gob.pe.consultaia.service.business.ChatGPTService;
import pj.gob.pe.consultaia.utils.beans.InputChatGPT;

import java.util.List;


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

    @Operation(summary = "Get de Peticionoes Historicas a ChatGPT", description = "Get de Peticiones a ChatGPT")
    @GetMapping("/list")
    public ResponseEntity<Page<CompletionsResponse>> list(
            @RequestHeader("SessionId") String SessionId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) throws Exception{

        Pageable pageable = PageRequest.of(page,size).withSort(Sort.Direction.DESC, "id");

        String buscar = "";

        Page<Completions> chatCompletionResponse = chatGPTService.listar(pageable, buscar, SessionId);

        if(chatCompletionResponse == null) {
            throw new ModeloNotFoundException("Error de procesamiento de Datos. Comunicarse con un administrador ");
        }

        for (Completions e : chatCompletionResponse.getContent()) {
            Hibernate.initialize(e.getConfigurations());
        }

        Page<CompletionsResponse> pageDTO = chatCompletionResponse.map(CompletionsResponse::new);

        return new ResponseEntity<>(pageDTO, HttpStatus.OK);
    }

    @Operation(summary = "Get Conversacion de ChatGPT", description = "Get Conversacion de ChatGPT")
    @GetMapping("/conversacion")
    public ResponseEntity<List<Completions>> list(
            @RequestHeader("SessionId") String SessionId,
            @RequestParam(name = "sessionuid", defaultValue = "0") String SessionUID) throws Exception{

        List<Completions> chatCompletionResponse = chatGPTService.getConversacion(SessionId, SessionUID);

        if(chatCompletionResponse == null) {
            throw new ModeloNotFoundException("Error de procesamiento de Datos. Comunicarse con un administrador ");
        }

        return new ResponseEntity<>(chatCompletionResponse, HttpStatus.OK);
    }
}
