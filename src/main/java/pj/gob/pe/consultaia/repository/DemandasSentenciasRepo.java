package pj.gob.pe.consultaia.repository;

import pj.gob.pe.consultaia.model.entities.DemandasSentencias;
import pj.gob.pe.consultaia.repository.custom.DemandasSentenciasCustomRepo;

public interface DemandasSentenciasRepo extends GenericRepo<DemandasSentencias, Long>, DemandasSentenciasCustomRepo {
}
