package pj.gob.pe.consultaia.service.externals.impl;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import pj.gob.pe.consultaia.configuration.ConfigProperties;
import pj.gob.pe.consultaia.configuration.RetryProperties;
import pj.gob.pe.consultaia.dao.mysql.ConfigurationDAO;
import pj.gob.pe.consultaia.exception.AuthOpenAIException;
import pj.gob.pe.consultaia.exception.HttpEstadoException;
import pj.gob.pe.consultaia.model.beans.input.MainRequest;
import pj.gob.pe.consultaia.model.beans.output.ChatCompletionResponse;
import pj.gob.pe.consultaia.service.externals.OpenAPIService;
import pj.gob.pe.consultaia.utils.ReintentoUtil;

import java.io.IOException;
import java.net.Proxy;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

@Service
public class OpenAPIServiceImpl implements OpenAPIService {

    private final RestClient restClient;
    private final ConfigurationDAO configurationDAO;
    private final ConfigProperties properties;
    private final RetryProperties retryProperties;


    //Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("http://172.17.16.213", 8988));

    public OpenAPIServiceImpl(RestClient.Builder builder, ConfigurationDAO configurationDAO,
                              ConfigProperties properties, RetryProperties retryProperties) {
        this.properties = properties;
        this.retryProperties = retryProperties;

        if(properties.getProxyEnabled()){
            HttpClient httpClient = HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress(properties.getProxyURL(), properties.getProxyPort())))
                    //.proxy(ProxySelector.of(new InetSocketAddress("172.17.16.213", 8988)))
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            this.restClient = builder.baseUrl(properties.getUrlOpenAI())
                    .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                    .build();
        } else {
            this.restClient = builder.baseUrl(properties.getUrlOpenAI()).build();
        }


        this.configurationDAO = configurationDAO;

    }

    @Override
    public ChatCompletionResponse consultaGPT_v1(MainRequest mainRequest) {

        // Realizar la petición con RestClient y mapear la respuesta directamente a TokenResponse.
        // Va envuelta en reintentos por la misma razón que el tráfico hacia Google: el egress sale
        // por el proxy institucional y una conexión cortada produce un fallo de transporte que se
        // resuelve repitiendo la llamada (ver ReintentoUtil).
        try {
            return ReintentoUtil.ejecutar(retryProperties, "OpenAI chat/completions", () ->
                    restClient.post()
                            .uri(properties.getPathChatGPT())
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getSecretKeyOpenAI())
                            .body(mainRequest)
                            .retrieve()
                            .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                                throw new AuthOpenAIException("Error de Credenciales de OpenAI");
                            })
                            .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                                // HttpEstadoException (y no RuntimeException) para que el 5xx sea
                                // reintentable; el mensaje que llega al llamador no cambia.
                                throw new HttpEstadoException(response.getStatusCode().value(),
                                        "Error del servidor, Comunicarse con el administrador");
                            })
                            .body(ChatCompletionResponse.class)); // Se convierte automáticamente el JSON a la clase Java
        } catch (IOException ex) {
            // Se conserva el mensaje original para no alterar lo que ya ve el llamador.
            throw new RuntimeException(ex.getMessage(), ex);
        }

    }
}
