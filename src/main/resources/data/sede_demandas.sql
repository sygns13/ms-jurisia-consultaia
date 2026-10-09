-- =====================================================================================
-- Sede SIJ en calificaciones y sentencias de demandas
-- Base de datos: JURISDB_CONSULTATIONIA
-- La sede se resuelve en GeminiServiceImpl/SentenciaServiceImpl cruzando la cinstancia con
-- las instancias y sedes del usuario (ms-jurisia-judicial) y viaja en el payload Kafka a
-- ms-jurisia-metricas (reportes admin por sede/instancia/expediente).
-- =====================================================================================

ALTER TABLE `JURISDB_CONSULTATIONIA`.`DemandasCalificadas`
    ADD COLUMN `codSede` char(20) DEFAULT NULL COMMENT 'Código de sede SIJ de la instancia' AFTER `xdescDemandante`,
    ADD COLUMN `sede` char(200) DEFAULT NULL COMMENT 'Descripción de sede SIJ de la instancia' AFTER `codSede`;

ALTER TABLE `JURISDB_CONSULTATIONIA`.`DemandasSentencias`
    ADD COLUMN `codSede` char(20) DEFAULT NULL COMMENT 'Código de sede SIJ de la instancia' AFTER `xdescDemandante`,
    ADD COLUMN `sede` char(200) DEFAULT NULL COMMENT 'Descripción de sede SIJ de la instancia' AFTER `codSede`;

-- Backfill de históricos (registros previos quedan con sede NULL).
-- Mapeo cinstancia -> sede tomado del SIJ (ver JURISDB_METRICS.CabDocumentoPlantillaGenerado).
-- VALIDAR el mapeo antes de ejecutar en cada entorno.
UPDATE `JURISDB_CONSULTATIONIA`.`DemandasCalificadas`
   SET `codSede` = '0201', `sede` = 'Sede Central de Corte'
 WHERE `codSede` IS NULL AND TRIM(`cinstancia`) IN ('301', '302');

UPDATE `JURISDB_CONSULTATIONIA`.`DemandasSentencias`
   SET `codSede` = '0201', `sede` = 'Sede Central de Corte'
 WHERE `codSede` IS NULL AND TRIM(`cinstancia`) IN ('301', '302');
