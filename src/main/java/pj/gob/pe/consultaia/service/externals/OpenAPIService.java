package pj.gob.pe.consultaia.service.externals;

import pj.gob.pe.consultaia.model.beans.input.MainRequest;
import pj.gob.pe.consultaia.model.beans.output.ChatCompletionResponse;

public interface OpenAPIService {

    public ChatCompletionResponse consultaGPT_v1(MainRequest mainRequest);
}
