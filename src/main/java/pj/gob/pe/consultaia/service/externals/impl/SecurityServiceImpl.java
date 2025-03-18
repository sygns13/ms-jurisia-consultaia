package pj.gob.pe.consultaia.service.externals.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import pj.gob.pe.consultaia.configuration.ConfigProperties;
import pj.gob.pe.consultaia.exception.AuthOpenAIException;
import pj.gob.pe.consultaia.service.externals.SecurityService;
import pj.gob.pe.consultaia.utils.beans.ResponseLogin;

@Service
public class SecurityServiceImpl implements SecurityService {

    private final RestClient restClient;
    private final ConfigProperties properties;

    public SecurityServiceImpl(RestClient.Builder builder, ConfigProperties properties) {
        this.restClient = builder.baseUrl(properties.getUrlSecurityAPI()).build();
        this.properties = properties;
    }

    @Override
    public ResponseLogin GetSessionData(String SessionId) {

        String pathSession = properties.getPathGetSession();

        pathSession = pathSession.replace(":sessionId", SessionId);

        return restClient.get()
                .uri(pathSession)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    throw new AuthOpenAIException("Credenciales de Sessión Expirada");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    throw new RuntimeException("Error del servidor, Comunicarse con el administrador");
                })
                .body(ResponseLogin.class); // Se convierte automáticamente el JSON a la clase Java
    }
}
