-- =====================================================================
-- Generación de documentos por plantilla completa (ms-jurisia-judicial,
-- /v1/documento-plantilla). Una sola llamada a Gemini por documento para
-- corregir la concordancia de los párrafos con datos insertados y
-- redactar los bloques de origen IA. Endpoint: POST /v1/gemini-documento/procesar
-- =====================================================================

-- Configuración del servicio. El modelo se copia de gemini_document_1 para
-- usar el mismo que el flujo por secciones; el roleSystem es propio.
-- El formato de la respuesta (JSON) lo fija el código, no este roleSystem.
insert into JURISDB_CONSULTATIONIA.Configurations
    (serviceCode, model, descripcion, roleSystem, promptDefault, maxMessages, temperature, activo, borrado, maxOutputTokens)
select
    'gemini_document_2',
    model,
    'Servicio de corrección y redacción de documentos por plantilla con Gemini',
    'Eres un asistente de redacción jurídica del Poder Judicial del Perú (Corte Superior de Justicia de Áncash). Corriges con precisión la ortografía, la gramática y la concordancia de documentos judiciales sin alterar su contenido jurídico, y redactas en registro formal, claro y conciso, sin inventar datos.',
    null,
    1,
    0.2,
    1,
    0,
    16384
from JURISDB_CONSULTATIONIA.Configurations
where serviceCode = 'gemini_document_1'
limit 1;

-- Registro de cada llamada (auditoría y trazabilidad por sessionUID con las
-- métricas de ms-jurisia-metricas). No es caché.
CREATE TABLE `JURISDB_CONSULTATIONIA`.`GeminiDocumentoPlantilla` (
`id` bigint NOT NULL AUTO_INCREMENT,
`sessionUID` char(50) NOT NULL,
`nUnico` bigint DEFAULT NULL,
`idDocumento` bigint DEFAULT NULL,
`idPlantilla` bigint DEFAULT NULL,
`codigoPlantilla` varchar(50) DEFAULT NULL,
`versionPlantilla` int DEFAULT NULL,
`userId` bigint DEFAULT NULL,
`model` char(50) DEFAULT NULL,
`roleSystem` text,
`temperature` decimal(3,1) DEFAULT NULL,
`request` mediumtext,
`response` mediumtext,
`parrafosEnviados` int DEFAULT NULL,
`parrafosCorregidos` int DEFAULT NULL,
`bloquesSolicitados` int DEFAULT NULL,
`bloquesGenerados` int DEFAULT NULL,
`promptTokens` int DEFAULT NULL,
`candidatesTokens` int DEFAULT NULL,
`thoughtsTokens` int DEFAULT NULL,
`cachedTokens` int DEFAULT NULL,
`totalTokens` int DEFAULT NULL,
`finishReason` varchar(50) DEFAULT NULL,
`fechaSend` datetime DEFAULT NULL,
`fechaResponse` datetime DEFAULT NULL,
`timeSeconds` double DEFAULT NULL,
`ConfigurationsId` int NOT NULL,
`status` tinyint DEFAULT NULL,
PRIMARY KEY (`id`),
KEY `IdxGdpSessionUID` (`sessionUID`),
KEY `IdxGdpNUnico` (`nUnico`),
KEY `IdxGdpIdPlantilla` (`idPlantilla`),
KEY `IdxGdpUserId` (`userId`),
KEY `IdxGdpFechaSend` (`fechaSend`),
KEY `IdxGdpStatus` (`status`),
KEY `fK_GeminiDocumentoPlantilla_Configurations` (`ConfigurationsId`),
CONSTRAINT `fK_GeminiDocumentoPlantilla_Configurations` FOREIGN KEY (`ConfigurationsId`) REFERENCES `JURISDB_CONSULTATIONIA`.`Configurations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
