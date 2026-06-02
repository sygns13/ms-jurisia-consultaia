package pj.gob.pe.consultaia.repository;

import pj.gob.pe.consultaia.model.entities.DemandasCalificadas;
import pj.gob.pe.consultaia.repository.custom.DemandasCalificadasCustomRepo;

public interface DemandasCalificadasRepo extends GenericRepo<DemandasCalificadas, Long>, DemandasCalificadasCustomRepo {
}
