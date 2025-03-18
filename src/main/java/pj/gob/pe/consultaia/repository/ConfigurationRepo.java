package pj.gob.pe.consultaia.repository;

import pj.gob.pe.consultaia.model.entities.Configurations;
import pj.gob.pe.consultaia.repository.custom.ConfigurationCustomRepo;

public interface ConfigurationRepo extends GenericRepo<Configurations, Long>, ConfigurationCustomRepo {
}
