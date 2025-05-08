package pj.gob.pe.consultaia.service.externals.impl;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import pj.gob.pe.consultaia.configuration.ConfigProperties;
import pj.gob.pe.consultaia.dao.mysql.ConfigurationDAO;
import pj.gob.pe.consultaia.exception.AuthOpenAIException;
import pj.gob.pe.consultaia.model.beans.input.MainRequest;
import pj.gob.pe.consultaia.model.beans.output.ChatCompletionResponse;
import pj.gob.pe.consultaia.service.externals.OpenAPIService;

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


    //Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("http://172.17.16.213", 8988));

    public OpenAPIServiceImpl(RestClient.Builder builder, ConfigurationDAO configurationDAO, ConfigProperties properties) {
        this.properties = properties;

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

        // Realizar la petición con RestClient y mapear la respuesta directamente a TokenResponse
        return restClient.post()
                .uri(properties.getPathChatGPT())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getSecretKeyOpenAI())
                .body(mainRequest)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    throw new AuthOpenAIException("Error de Credenciales de OpenAI");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    throw new RuntimeException("Error del servidor, Comunicarse con el administrador");
                })
                .body(ChatCompletionResponse.class); // Se convierte automáticamente el JSON a la clase Java

    }
}
