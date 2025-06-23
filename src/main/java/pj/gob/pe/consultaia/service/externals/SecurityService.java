package pj.gob.pe.consultaia.service.externals;

import pj.gob.pe.consultaia.utils.beans.responses.ResponseLogin;

public interface SecurityService {

    public ResponseLogin GetSessionData(String SessionId);
}
