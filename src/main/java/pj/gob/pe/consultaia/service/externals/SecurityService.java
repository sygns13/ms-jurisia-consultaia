package pj.gob.pe.consultaia.service.externals;

import pj.gob.pe.consultaia.utils.beans.ResponseLogin;

public interface SecurityService {

    public ResponseLogin GetSessionData(String SessionId);
}
