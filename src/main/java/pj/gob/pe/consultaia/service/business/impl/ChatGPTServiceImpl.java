package pj.gob.pe.consultaia.service.business.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import pj.gob.pe.consultaia.dao.mysql.CompletionDAO;
import pj.gob.pe.consultaia.dao.mysql.ConfigurationDAO;
import pj.gob.pe.consultaia.dao.mysql.ExpedienteCompletionDAO;
import pj.gob.pe.consultaia.exception.ValidationServiceException;
import pj.gob.pe.consultaia.exception.ValidationSessionServiceException;
import pj.gob.pe.consultaia.model.beans.input.MainRequest;
import pj.gob.pe.consultaia.model.beans.input.Message;
import pj.gob.pe.consultaia.model.beans.output.ChatCompletionResponse;
import pj.gob.pe.consultaia.model.beans.output.Choice;
import pj.gob.pe.consultaia.model.entities.Completions;
import pj.gob.pe.consultaia.model.entities.Configurations;
import pj.gob.pe.consultaia.model.entities.ExpedienteCompletion;
import pj.gob.pe.consultaia.service.business.ChatGPTService;
import pj.gob.pe.consultaia.service.externals.JudicialService;
import pj.gob.pe.consultaia.service.externals.OpenAPIService;
import pj.gob.pe.consultaia.service.externals.SecurityService;
import pj.gob.pe.consultaia.utils.Constantes;
import pj.gob.pe.consultaia.utils.beans.*;
import pj.gob.pe.consultaia.utils.beans.inputs.InputChatGPT;
import pj.gob.pe.consultaia.utils.beans.inputs.InputDocument;
import pj.gob.pe.consultaia.utils.beans.responses.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class ChatGPTServiceImpl implements ChatGPTService {

    private final SecurityService securityService;
    private final JudicialService judicialService;
    private final ConfigurationDAO configurationDAO;
    private final CompletionDAO completionDAO;
    private final ExpedienteCompletionDAO expedienteCompletionDAO;
    private final OpenAPIService openAPIService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private final String serviceChatGPT1 = "chat_gpt_1";
    private final String serviceChatGPT2 = "chat_gpt_2";

    private static final Logger logger = LoggerFactory.getLogger(ChatGPTServiceImpl.class);

    @Override
    public Completions processChatGPT(InputChatGPT inputChatGPT, String SessionId) throws Exception {

        String errorValidacion = "";

        if(SessionId == null || SessionId.isEmpty()){
            errorValidacion = "La sessión remitida es inválida";
            throw new ValidationSessionServiceException(errorValidacion);
        }

        ResponseLogin responseLogin = securityService.GetSessionData(SessionId);

        if(responseLogin == null || !responseLogin.isSuccess() || !responseLogin.isItemFound() || responseLogin.getUser() == null){
            errorValidacion = "La sessión remitida es inválida";
            throw new ValidationSessionServiceException(errorValidacion);
        }

        Map<String, Object> filters = new HashMap<>();
        filters.put("borrado", Constantes.REGISTRO_NO_BORRADO);
        filters.put("activo", Constantes.REGISTRO_ACTIVO);
        filters.put("serviceCode", serviceChatGPT1);

        Configurations configurations = configurationDAO.getConfigurationsByFilters(filters);

        if(configurations == null || configurations.getId() == null){
            errorValidacion = "La Configuración de Comunicación con la IA no está realizada adecuadamente, comunicarlo a un administrador";
            throw new ValidationServiceException(errorValidacion);
        }

        List<Completions> historyCompletions = new ArrayList<>();

        if(inputChatGPT.getSessionUID() != null && !inputChatGPT.getSessionUID().isEmpty()){
            filters.clear();
            filters.put("userId", responseLogin.getUser().getIdUser());
            filters.put("sessionUID", inputChatGPT.getSessionUID());

            Map<String, Object> filtersNotEquals = new HashMap<>();

            String OrderByID = "id";

            historyCompletions = completionDAO.getCompletionsByFilters(filters, filtersNotEquals, configurations.getMaxMessages(), OrderByID);

            // Invertir el orden de la lista
            Collections.reverse(historyCompletions);
        } else {
            inputChatGPT.setSessionUID(UUID.randomUUID().toString());
        }

        MainRequest mainRequest = new MainRequest();

        mainRequest.setModel(configurations.getModel());
        mainRequest.setTemperature(configurations.getTemperature().setScale(1, RoundingMode.HALF_UP));

        List<Message> messages = new ArrayList<>();

        Message message = new Message();

        message.setRole("system");
        message.setContent(configurations.getRoleSystem());

        messages.add(message);

        historyCompletions.forEach((completions) -> {

            if (completions != null && completions.getRoleUser() != null && completions.getRoleContent() != null) {
                Message messageHistory = new Message();
                messageHistory.setRole("user");
                messageHistory.setContent(completions.getRoleUser());

                messages.add(messageHistory);

                Message messageHistory2 = new Message();
                messageHistory2.setRole("assistant");
                messageHistory2.setContent(completions.getRoleContent());

                messages.add(messageHistory2);
            }
        });

        Message messageEnd = new Message();
        messageEnd.setRole("user");
        messageEnd.setContent(inputChatGPT.getPrompt());

        messages.add(messageEnd);

        mainRequest.setMessages(messages);

        //Save New Completions
        Completions responseCompletions = new Completions();
        LocalDateTime fechaActualTime = LocalDateTime.now();

        responseCompletions.setUserId(responseLogin.getUser().getIdUser());
        responseCompletions.setModel(mainRequest.getModel());
        responseCompletions.setRoleSystem(configurations.getRoleSystem());
        responseCompletions.setRoleUser(inputChatGPT.getPrompt());
        responseCompletions.setTemperature(mainRequest.getTemperature());
        responseCompletions.setFechaSend(fechaActualTime);

        responseCompletions.setConfigurations(configurations);
        responseCompletions.setSessionUID(inputChatGPT.getSessionUID());
        responseCompletions.setStatus(Constantes.COMPLETION_INICIADO);

        responseCompletions = completionDAO.registrar(responseCompletions);


        ChatCompletionResponse response = openAPIService.consultaGPT_v1(mainRequest);

        if(response == null || response.getId() == null){
            responseCompletions.setStatus(Constantes.COMPLETION_ERROR);
            responseCompletions = completionDAO.modificar(responseCompletions);
            
            return responseCompletions;
        }

        fechaActualTime = LocalDateTime.now();

        responseCompletions.setFechaResponse(fechaActualTime);
        responseCompletions.setIdGpt(response.getId());
        responseCompletions.setObject(response.getObject());
        responseCompletions.setCreated(response.getCreated());
        responseCompletions.setModelResponse(response.getModel());

        if(!response.getChoices().isEmpty()){
            Choice choiseZero = response.getChoices().get(0);
            responseCompletions.setRoleResponse(choiseZero.getMessage().getRole());
            responseCompletions.setRoleContent(choiseZero.getMessage().getContent());
            responseCompletions.setRefusal(choiseZero.getMessage().getRefusal());
            responseCompletions.setLogprobs(choiseZero.getLogprobs());
            responseCompletions.setFinishReason(choiseZero.getFinishReason());
        }

        //TODO: Agregar campos
        if(response.getUsage() != null){
            responseCompletions.setPromptTokens(response.getUsage().getPromptTokens());
            responseCompletions.setCompletionTokens(response.getUsage().getCompletionTokens());
            responseCompletions.setTotalTokens(response.getUsage().getTotalTokens());

            if(response.getUsage().getPromptTokensDetails() != null){
                responseCompletions.setCachedTokens(response.getUsage().getPromptTokensDetails().getCachedTokens());
                responseCompletions.setAudioTokens(response.getUsage().getPromptTokensDetails().getAudioTokens());
            }

            if(response.getUsage().getCompletionTokensDetails() != null){
                responseCompletions.setCompletionReasoningTokens(response.getUsage().getCompletionTokensDetails().getReasoningTokens());
                responseCompletions.setCompletionAudioTokens(response.getUsage().getCompletionTokensDetails().getAudioTokens());
                responseCompletions.setCompletionAceptedTokens(response.getUsage().getCompletionTokensDetails().getAcceptedPredictionTokens());
                responseCompletions.setCompletionRejectedTokens(response.getUsage().getCompletionTokensDetails().getRejectedPredictionTokens());
            }
        }

        responseCompletions.setServiceTier(response.getServiceTier());
        responseCompletions.setSystemFingerprint(response.getSystemFingerprint());
        responseCompletions.setStatus(Constantes.COMPLETION_EXITOSO);

        responseCompletions = completionDAO.modificar(responseCompletions);


        // Enviar mensaje a Metrics by Kafka
        //String json = objectMapper.writeValueAsString(responseCompletions);
        List<DataSedeDTO> sedesDTO = judicialService.GetSedes(SessionId);
        List<DataInstanciaDTO> instanciasDTOS = judicialService.GetInstancias(SessionId);

        List<Sedes> sedes = new ArrayList<>();

        sedesDTO.forEach( sedeDTO -> {
            Sedes sede = new Sedes();
            sede.setCodSede(sedeDTO.getCodigoSede());
            sede.setSede(sedeDTO.getSede());

            List<Instancias> instancias = new ArrayList<>();

            instanciasDTOS.forEach( instanciaDTO -> {
                Instancias instancia = new Instancias();

                instancia.setCodInstancia(instanciaDTO.getCodigoInstancia());
                instancia.setInstancia(instanciaDTO.getInstancia());

                instancias.add(instancia);
            });

            sede.setInstancias(instancias);
            sedes.add(sede);
        });

        responseCompletions.setSedes(sedes);
        responseCompletions.setConfigurationsId(configurations.getId());
        kafkaTemplate.send("judicial-metrics", "key1", responseCompletions);

        return responseCompletions;
    }

    @Override
    public Page<Completions> listar(Pageable pageable, String buscar, String SessionId) throws Exception {

        String errorValidacion = "";

        if(SessionId == null || SessionId.isEmpty()){
            errorValidacion = "La sessión remitida es inválida";
            throw new ValidationSessionServiceException(errorValidacion);
        }

        ResponseLogin responseLogin = securityService.GetSessionData(SessionId);

        if(responseLogin == null || !responseLogin.isSuccess() || !responseLogin.isItemFound() || responseLogin.getUser() == null){
            errorValidacion = "La sessión remitida es inválida";
            throw new ValidationSessionServiceException(errorValidacion);
        }

        Map<String, Object> filters = new HashMap<>();
        filters.put("userId", responseLogin.getUser().getIdUser());

        Map<String, Object> filtersNotEquals = new HashMap<>();

        Page<Completions> completionsPage = completionDAO.getGralCompletionsByFilters(filters, filtersNotEquals, pageable);

        return completionsPage;
    }

    @Override
    public List<Completions> getConversacion(String SessionId, String sessionUIDConversacion) throws Exception {

        String errorValidacion = "";

        if(SessionId == null || SessionId.isEmpty()){
            errorValidacion = "La sessión remitida es inválida";
            throw new ValidationSessionServiceException(errorValidacion);
        }

        ResponseLogin responseLogin = securityService.GetSessionData(SessionId);

        if(responseLogin == null || !responseLogin.isSuccess() || !responseLogin.isItemFound() || responseLogin.getUser() == null){
            errorValidacion = "La sessión remitida es inválida";
            throw new ValidationSessionServiceException(errorValidacion);
        }

        Map<String, Object> filters = new HashMap<>();
        filters.put("userId", responseLogin.getUser().getIdUser());
        filters.put("sessionUID", sessionUIDConversacion);

        Map<String, Object> filtersNotEquals = new HashMap<>();

        String OrderByID = "id";
        Integer Limit = Constantes.CANTIDAD_MIL_INTEGER;

        List<Completions> historyCompletions = completionDAO.getCompletionsByFilters(filters, filtersNotEquals, Limit, OrderByID);

        // Invertir el orden de la lista
        Collections.reverse(historyCompletions);

        return historyCompletions;
    }

    @Override
    public Long getTotalConversaciones(String buscar, String SessionId) throws Exception {

        String errorValidacion = "";

        if(SessionId == null || SessionId.isEmpty()){
            errorValidacion = "La sessión remitida es inválida";
            throw new ValidationSessionServiceException(errorValidacion);
        }

        ResponseLogin responseLogin = securityService.GetSessionData(SessionId);

        if(responseLogin == null || !responseLogin.isSuccess() || !responseLogin.isItemFound() || responseLogin.getUser() == null){
            errorValidacion = "La sessión remitida es inválida";
            throw new ValidationSessionServiceException(errorValidacion);
        }

        Map<String, Object> filters = new HashMap<>();
        filters.put("userId", responseLogin.getUser().getIdUser());

        Map<String, Object> filtersNotEquals = new HashMap<>();

        Long totalElementos = completionDAO.getTotalConversaciones(filters, filtersNotEquals);

        return totalElementos;
    }

    @Override
    public ResponseDocument processDocument(InputDocument inputDocument) throws Exception {

        Map<String, Object> filters = new HashMap<>();
        filters.put("borrado", Constantes.REGISTRO_NO_BORRADO);
        filters.put("activo", Constantes.REGISTRO_ACTIVO);
        filters.put("serviceCode", serviceChatGPT2);

        String errorValidacion = "";

        Configurations configurations = configurationDAO.getConfigurationsByFilters(filters);

        if(configurations == null || configurations.getId() == null){
            errorValidacion = "La Configuración de Comunicación con la IA no está realizada adecuadamente, comunicarlo a un administrador";
            throw new ValidationServiceException(errorValidacion);
        }

        List<SectionTemplate> sectionTemplatesResponse = new ArrayList<>();

        for(SectionTemplate sectionTemplate : inputDocument.getSectionTemplates()) {
            SectionTemplate newSectionTemplate = new SectionTemplate();

            newSectionTemplate.setId(sectionTemplate.getId());
            newSectionTemplate.setIdTemplate(sectionTemplate.getIdTemplate());
            newSectionTemplate.setCodigo(sectionTemplate.getCodigo());
            newSectionTemplate.setContent(sectionTemplate.getContent());
            newSectionTemplate.setDescripcion(sectionTemplate.getDescripcion());
            newSectionTemplate.setIsFinal(sectionTemplate.getIsFinal());
            newSectionTemplate.setIsBold(sectionTemplate.getIsBold());
            newSectionTemplate.setIsSendIA(sectionTemplate.getIsSendIA());
            newSectionTemplate.setIsSaltoLinea(sectionTemplate.getIsSaltoLinea());
            newSectionTemplate.setIsProcessed(false);

            if(sectionTemplate.getIsSendIA().equals(Constantes.REGISTRO_INACTIVO))
                newSectionTemplate.setIsProcessed(true);

            sectionTemplatesResponse.add(newSectionTemplate);
        }

        List<ExpedienteCompletion> expCompletions = expedienteCompletionDAO.findExpedienteCompletions(inputDocument.getNUnico(), inputDocument.getCodeTemplate());



        String model = configurations.getModel();
        String roleSystem = configurations.getRoleSystem();
        BigDecimal temperature = configurations.getTemperature().setScale(1, RoundingMode.HALF_UP);
        AtomicReference<String> object = new AtomicReference<>("");
        AtomicReference<String> modelResponse = new AtomicReference<>("");
        AtomicReference<String> roleResponse = new AtomicReference<>("");
        AtomicReference<Integer> logprobs = new AtomicReference<>(0);
        AtomicReference<String> finishReason = new AtomicReference<>("");
        AtomicReference<Integer> promptTokens = new AtomicReference<>(0);
        AtomicReference<Integer> completionTokens = new AtomicReference<>(0);
        AtomicReference<Integer> totalTokens = new AtomicReference<>(0);
        AtomicReference<Integer> cachedTokens = new AtomicReference<>(0);
        AtomicReference<Integer> audioTokens = new AtomicReference<>(0);
        AtomicReference<Integer> completionReasoningTokens = new AtomicReference<>(0);
        AtomicReference<Integer> completionAudioTokens = new AtomicReference<>(0);
        AtomicReference<Integer> completionAceptedTokens = new AtomicReference<>(0);
        AtomicReference<Integer> completionRejectedTokens = new AtomicReference<>(0);
        AtomicReference<String> serviceTier = new AtomicReference<>("");
        Integer configurationsId = configurations.getId();
        String UIDSession = UUID.randomUUID().toString();

        sectionTemplatesResponse.forEach(sectionTemplate -> {

            if(expCompletions != null && !expCompletions.isEmpty()) {
                for (ExpedienteCompletion expedienteCompletion : expCompletions) {
                    if (expedienteCompletion.getSectionId().equals(sectionTemplate.getId())) {
                        sectionTemplate.setContent(expedienteCompletion.getRoleContent());
                        sectionTemplate.setIsProcessed(true);
                        break;
                    }
                }
            }

            if(!sectionTemplate.getIsProcessed()){

                MainRequest mainRequest = new MainRequest();

                mainRequest.setModel(configurations.getModel());
                mainRequest.setTemperature(configurations.getTemperature().setScale(1, RoundingMode.HALF_UP));

                List<Message> messages = new ArrayList<>();
                Message message = new Message();

                message.setRole("system");
                message.setContent(configurations.getRoleSystem());

                messages.add(message);

                Message messageEnd = new Message();
                messageEnd.setRole("user");
                messageEnd.setContent(sectionTemplate.getContent());

                messages.add(messageEnd);

                mainRequest.setMessages(messages);

                //Save New Completions
                ExpedienteCompletion responseCompletions = new ExpedienteCompletion();
                LocalDateTime fechaActualTime = LocalDateTime.now();

                responseCompletions.setNUnico(inputDocument.getNUnico());
                responseCompletions.setTemplateCode(inputDocument.getCodeTemplate());
                responseCompletions.setSectionId(sectionTemplate.getId());
                responseCompletions.setUserId(inputDocument.getIdUser());
                responseCompletions.setModel(mainRequest.getModel());
                responseCompletions.setRoleSystem(configurations.getRoleSystem());
                responseCompletions.setRoleUser(sectionTemplate.getContent());
                responseCompletions.setTemperature(mainRequest.getTemperature());
                responseCompletions.setFechaSend(fechaActualTime);

                responseCompletions.setConfigurations(configurations);
                responseCompletions.setSessionUID(UIDSession);
                responseCompletions.setStatus(Constantes.COMPLETION_INICIADO);

                try {
                    responseCompletions = expedienteCompletionDAO.registrar(responseCompletions);
                } catch (Exception e) {
                    logger.error("Error obteniendo en expedienteCompletionDAO.registrar(responseCompletions): {}", e.getMessage());
                    String erValidacion = "El registro de Data no se ha realizado correctamente, por favor comunicarlo a un administrador";
                    throw new ValidationServiceException(erValidacion);
                }

                ChatCompletionResponse response = openAPIService.consultaGPT_v1(mainRequest);

                if(response == null || response.getId() == null){
                    responseCompletions.setStatus(Constantes.COMPLETION_ERROR);
                    try {
                        responseCompletions = expedienteCompletionDAO.modificar(responseCompletions);
                    } catch (Exception e) {
                        logger.error("Error obteniendo en expedienteCompletionDAO.modificar(responseCompletions): {}", e.getMessage());
                    }
                }

                object.set(response.getObject());

                responseCompletions.setFechaResponse(fechaActualTime);
                responseCompletions.setIdGpt(response.getId());
                responseCompletions.setObject(response.getObject());
                responseCompletions.setCreated(response.getCreated());
                responseCompletions.setModelResponse(response.getModel());

                modelResponse.set(responseCompletions.getModelResponse());

                if(!response.getChoices().isEmpty()){
                    Choice choiseZero = response.getChoices().get(0);
                    responseCompletions.setRoleResponse(choiseZero.getMessage().getRole());
                    responseCompletions.setRoleContent(choiseZero.getMessage().getContent());
                    responseCompletions.setRefusal(choiseZero.getMessage().getRefusal());
                    responseCompletions.setLogprobs(choiseZero.getLogprobs());
                    responseCompletions.setFinishReason(choiseZero.getFinishReason());

                    roleResponse.set(responseCompletions.getRoleResponse());
                    logprobs.set(responseCompletions.getLogprobs());
                    finishReason.set(responseCompletions.getFinishReason());
                }

                //TODO: Agregar campos
                if(response.getUsage() != null){
                    responseCompletions.setPromptTokens(response.getUsage().getPromptTokens());
                    responseCompletions.setCompletionTokens(response.getUsage().getCompletionTokens());
                    responseCompletions.setTotalTokens(response.getUsage().getTotalTokens());

                    promptTokens.set(promptTokens.get() + responseCompletions.getPromptTokens());
                    completionTokens.set(completionTokens.get() + responseCompletions.getCompletionTokens());
                    totalTokens.set(totalTokens.get() + responseCompletions.getTotalTokens());

                    if(response.getUsage().getPromptTokensDetails() != null){
                        responseCompletions.setCachedTokens(response.getUsage().getPromptTokensDetails().getCachedTokens());
                        responseCompletions.setAudioTokens(response.getUsage().getPromptTokensDetails().getAudioTokens());

                        cachedTokens.set(cachedTokens.get() + responseCompletions.getCachedTokens());
                        audioTokens.set(audioTokens.get() + responseCompletions.getAudioTokens());
                    }

                    if(response.getUsage().getCompletionTokensDetails() != null){
                        responseCompletions.setCompletionReasoningTokens(response.getUsage().getCompletionTokensDetails().getReasoningTokens());
                        responseCompletions.setCompletionAudioTokens(response.getUsage().getCompletionTokensDetails().getAudioTokens());
                        responseCompletions.setCompletionAceptedTokens(response.getUsage().getCompletionTokensDetails().getAcceptedPredictionTokens());
                        responseCompletions.setCompletionRejectedTokens(response.getUsage().getCompletionTokensDetails().getRejectedPredictionTokens());

                        completionReasoningTokens.set(completionReasoningTokens.get() + responseCompletions.getCompletionReasoningTokens());
                        completionAudioTokens.set(completionAudioTokens.get() + responseCompletions.getCompletionAudioTokens());
                        completionAceptedTokens.set(completionAceptedTokens.get() + responseCompletions.getCompletionAceptedTokens());
                        completionRejectedTokens.set(completionRejectedTokens.get() + responseCompletions.getCompletionRejectedTokens());
                    }
                }

                responseCompletions.setServiceTier(response.getServiceTier());
                responseCompletions.setSystemFingerprint(response.getSystemFingerprint());
                responseCompletions.setStatus(Constantes.COMPLETION_EXITOSO);

                serviceTier.set(responseCompletions.getServiceTier());

                try {
                    responseCompletions = expedienteCompletionDAO.modificar(responseCompletions);
                } catch (Exception e) {
                    logger.error("Error obteniendo en expedienteCompletionDAO.modificar(responseCompletions): {}", e.getMessage());
                }

                sectionTemplate.setContent(responseCompletions.getRoleContent());
                sectionTemplate.setIsProcessed(true);
            }
        });


        ResponseDocument responseDocument = new ResponseDocument();

        responseDocument.setNUnico(inputDocument.getNUnico());
        responseDocument.setCodeTemplate(inputDocument.getCodeTemplate());
        responseDocument.setSectionTemplates(sectionTemplatesResponse);

        responseDocument.setModel(model);
        responseDocument.setRoleSystem(roleSystem);
        responseDocument.setTemperature(temperature);
        responseDocument.setObject(object.get());
        responseDocument.setModelResponse(modelResponse.get());
        responseDocument.setRoleResponse(roleResponse.get());
        responseDocument.setLogprobs(logprobs.get());
        responseDocument.setFinishReason(finishReason.get());
        responseDocument.setPromptTokens(promptTokens.get());
        responseDocument.setCompletionTokens(completionTokens.get());
        responseDocument.setTotalTokens(totalTokens.get());
        responseDocument.setCachedTokens(cachedTokens.get());
        responseDocument.setAudioTokens(audioTokens.get());
        responseDocument.setCompletionReasoningTokens(completionReasoningTokens.get());
        responseDocument.setCompletionAudioTokens(completionAudioTokens.get());
        responseDocument.setCompletionAceptedTokens(completionAceptedTokens.get());
        responseDocument.setCompletionRejectedTokens(completionRejectedTokens.get());
        responseDocument.setServiceTier(serviceTier.get());
        responseDocument.setConfigurationsId(configurationsId);
        responseDocument.setSessionUID(UIDSession);


        return responseDocument;
    }
}
