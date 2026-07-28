-- =====================================================================
-- Módulo conversacional con Gemini (chat multimodal con adjuntos)
-- Nota: la tabla GeminiChats y el registro gemini_chat_1 de Configurations
-- ya fueron creados por backup.sql. Este script agrega lo nuevo del módulo.
-- =====================================================================

-- Adjuntos por cada turno de chat: nombre original del archivo y su
-- dirección (URI gs:// en el bucket pj_gemini_chat_adjuntos). Para Word
-- (.doc/.docx) se guarda además el texto extraído con POI, que es lo que
-- se envía a Gemini (no acepta Word nativo) y permite reinyectar el
-- documento en el historial de turnos posteriores.
CREATE TABLE `JURISDB_CONSULTATIONIA`.`GeminiChatsFiles` (
`id` bigint NOT NULL AUTO_INCREMENT,
`geminiChatId` bigint NOT NULL,
`sessionUID` char(50) NOT NULL,
`fileName` varchar(255) DEFAULT NULL,
`mimeType` varchar(150) DEFAULT NULL,
`sizeBytes` bigint DEFAULT NULL,
`gcsUri` varchar(500) DEFAULT NULL,
`textoExtraido` longtext,
`fechaReg` datetime DEFAULT NULL,
`status` tinyint DEFAULT NULL,
PRIMARY KEY (`id`),
KEY `IdxGeminiChatId` (`geminiChatId`),
KEY `IdxSessionUID` (`sessionUID`),
CONSTRAINT `fK_GeminiChatsFiles_GeminiChats` FOREIGN KEY (`geminiChatId`) REFERENCES `JURISDB_CONSULTATIONIA`.`GeminiChats` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Modelo del chat conversacional Gemini (punto 4 de la guía del requerimiento).
UPDATE `JURISDB_CONSULTATIONIA`.`Configurations`
SET `model` = 'gemini-3.6-flash'
WHERE `serviceCode` = 'gemini_chat_1';
