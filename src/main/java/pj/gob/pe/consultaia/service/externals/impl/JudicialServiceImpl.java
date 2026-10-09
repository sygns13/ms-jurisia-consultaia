package pj.gob.pe.consultaia.service.externals.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Objects;

@Service
public class JudicialServiceImpl implements JudicialService {

    private static final Logger logger = LoggerFactory.getLogger(JudicialServiceImpl.class);

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

    @Override
    public DataSedeDTO GetSedePorInstancia(String SessionId, String cinstancia) {
        if (cinstancia == null || cinstancia.trim().isEmpty()) {
            return null;
        }
        try {
            // Los códigos del SIJ (Sybase) pueden venir con espacios de relleno: se comparan con trim.
            String codigoSede = GetInstancias(SessionId).stream()
                    .filter(i -> i.getCodigoInstancia() != null && i.getCodigoInstancia().trim().equals(cinstancia.trim()))
                    .map(DataInstanciaDTO::getCodigoSede)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .findFirst()
                    .orElse(null);

            if (codigoSede == null) {
                return null;
            }

            return GetSedes(SessionId).stream()
                    .filter(s -> s.getCodigoSede() != null && s.getCodigoSede().trim().equals(codigoSede))
                    .findFirst()
                    .orElseGet(() -> {
                        DataSedeDTO sede = new DataSedeDTO();
                        sede.setCodigoSede(codigoSede);
                        return sede;
                    });
        } catch (Exception ex) {
            logger.warn("No se pudo resolver la sede de la instancia {}: {}", cinstancia, ex.getMessage());
            return null;
        }
    }
}
