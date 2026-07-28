package pj.gob.pe.consultaia.repository;

import pj.gob.pe.consultaia.model.entities.GeminiChats;
import pj.gob.pe.consultaia.repository.custom.GeminiChatsCustomRepo;

public interface GeminiChatsRepo extends GenericRepo<GeminiChats, Long>, GeminiChatsCustomRepo {
}
