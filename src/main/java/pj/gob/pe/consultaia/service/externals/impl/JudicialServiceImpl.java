package pj.gob.pe.consultaia.service.externals.impl;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import pj.gob.pe.consultaia.configuration.ConfigProperties;
import pj.gob.pe.consultaia.exception.AuthOpenAIException;
import pj.gob.pe.consultaia.service.externals.JudicialService;
import pj.gob.pe.consultaia.utils.beans.responses.DataInstanciaDTO;
import pj.gob.pe.consultaia.utils.beans.responses.DataSedeDTO;

import java.util.List;

@Service
public class JudicialServiceImpl implements JudicialService {

    private final RestClient restClient;
    private final ConfigProperties properties;

    public JudicialServiceImpl(RestClient.Builder builder, ConfigProperties properties) {
        this.restClient = builder.baseUrl(properties.getUrlJudicialAPI()).build();
        this.properties = properties;
    }

    @Override
    public List<DataSedeDTO> GetSedes(String SessionId) {
        String path = properties.getPathSedes();

        return restClient.get()
                .uri(path)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("SessionId", SessionId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    throw new AuthOpenAIException("Credenciales de Sessión Expirada");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    throw new RuntimeException("Error del servidor, Comunicarse con el administrador");
                })
                .body(new ParameterizedTypeReference<List<DataSedeDTO>>() {});
    }

    @Override
    public List<DataInstanciaDTO> GetInstancias(String SessionId) {
        String path = properties.getPathInstancias();

        return restClient.get()
                .uri(path)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("SessionId", SessionId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    throw new AuthOpenAIException("Credenciales de Sessión Expirada");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    throw new RuntimeException("Error del servidor, Comunicarse con el administrador");
                })
                .body(new ParameterizedTypeReference<List<DataInstanciaDTO>>() {});
    }
}
