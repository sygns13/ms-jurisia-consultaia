-- =====================================================================
-- Procesamiento de documentos por secciones con Gemini (corrección
-- ortotipográfica), espejo del flujo chat_gpt_2 / ExpedienteCompletions.
-- =====================================================================

-- Configuración del servicio: mismo roleSystem/comportamiento que chat_gpt_2
-- (corrector ortotipográfico) pero con el modelo gemini-3.6-flash. Se copia el
-- roleSystem con INSERT..SELECT para que quede idéntico al del flujo OpenAI.
insert into JURISDB_CONSULTATIONIA.Configurations
    (serviceCode, model, descripcion, roleSystem, promptDefault, maxMessages, temperature, activo, borrado, maxOutputTokens)
select
    'gemini_document_1',
    'gemini-3.6-flash',
    'Servicio de Corrección de Textos de la IA con Gemini',
    roleSystem,
    promptDefault,
    maxMessages,
    temperature,
    1,
    0,
    8192
from JURISDB_CONSULTATIONIA.Configurations
where serviceCode = 'chat_gpt_2'
limit 1;

-- Caché/registro de correcciones por sección (espejo de ExpedienteCompletions,
-- sin las columnas de tokens de OpenAI). La reutilización es por
-- nUnico + templateCode + sectionId con ventana de 7 días sobre fechaResponse.
CREATE TABLE `JURISDB_CONSULTATIONIA`.`GeminiExpedienteChats` (
`id` bigint NOT NULL AUTO_INCREMENT,
`nUnico` bigint DEFAULT NULL,
`templateCode` char(50) NOT NULL,
`sectionId` bigint DEFAULT NULL,
`userId` bigint DEFAULT NULL,
`model` char(50) DEFAULT NULL,
`roleSystem` text,
`roleUser` text,
`temperature` decimal(3,1) DEFAULT NULL,
`fechaSend` datetime DEFAULT NULL,
`fechaResponse` datetime DEFAULT NULL,
`response` text,
`timeSeconds` double DEFAULT NULL,
`ConfigurationsId` int NOT NULL,
`sessionUID` char(50) NOT NULL,
`status` tinyint DEFAULT NULL,
PRIMARY KEY (`id`),
KEY `IdxNUnico` (`nUnico`),
KEY `IdxTemplateCode` (`templateCode`),
KEY `IdxSectionId` (`sectionId`),
KEY `IdxUserId` (`userId`),
KEY `IdxSessionUID` (`sessionUID`),
KEY `IdxFechaResponse` (`fechaResponse`),
KEY `IdxStatus` (`status`),
KEY `fK_GeminiExpedienteChats_Configurations` (`ConfigurationsId`),
CONSTRAINT `fK_GeminiExpedienteChats_Configurations` FOREIGN KEY (`ConfigurationsId`) REFERENCES `JURISDB_CONSULTATIONIA`.`Configurations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
