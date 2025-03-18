package pj.gob.pe.consultaia.repository;

import pj.gob.pe.consultaia.model.entities.Completions;
import pj.gob.pe.consultaia.repository.custom.CompletionCustomRepo;

public interface CompletionRepo extends GenericRepo<Completions, Long>, CompletionCustomRepo {
}
