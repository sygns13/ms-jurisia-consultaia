-- ============================================================================
-- Migración: ampliar columna xnombreArchivo de 100 a 500 caracteres.
--
-- Motivo: una demanda puede estar formada por 1..n archivos PDF. El nombre de
-- cada archivo ya no se persiste individualmente; se almacenan todos unidos
-- (separados por '; '), por lo que 100 caracteres resultan insuficientes.
--
-- Ejecutar en AMBAS bases de datos (consultaia y métricas), ya que la tabla
-- DemandasCalificadas se replica vía Kafka.
-- ============================================================================

ALTER TABLE `JURISDB_CONSULTATIONIA`.`DemandasCalificadas`
    MODIFY COLUMN `xnombreArchivo` varchar(500) DEFAULT NULL;

ALTER TABLE `JURISDB_METRICS`.`DemandasCalificadas`
    MODIFY COLUMN `xnombreArchivo` varchar(500) DEFAULT NULL;
