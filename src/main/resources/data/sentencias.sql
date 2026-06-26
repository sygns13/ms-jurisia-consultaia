-- =====================================================================================
-- Flujo de GENERACIÓN DE SENTENCIAS (espejo del flujo de calificación de demandas)
-- Base de datos: JURISDB_CONSULTATIONIA
-- =====================================================================================

-- 1) Configuración del servicio de generación de sentencia (Configurations.serviceCode = 'geminy_sentencia_1').
--    El SentenciaServiceImpl sobrescribe roleSystem y promptDefault en código; de aquí se toman
--    model, temperature y maxOutputTokens. Independiente de la configuración de calificación.
INSERT INTO JURISDB_CONSULTATIONIA.Configurations
    (serviceCode, model, descripcion, roleSystem, promptDefault, maxMessages, temperature, activo, borrado, maxOutputTokens)
VALUES
    ('geminy_sentencia_1', 'gemini-3.1-pro-preview', 'Servicio de Generación de la Sentencia de la Demanda',
     'Eres un Asistente Judicial Virtual experto en derecho peruano con amplia experiencia en legislación, normativas y jurisprudencia. A partir de una demanda ingresada generas automáticamente el borrador de la SENTENCIA correspondiente. Tu respuesta debe ser directamente el texto de la sentencia.',
     'Por favor genera la sentencia de la demanda, tu respuesta debe contener solo el borrador de la sentencia judicial',
     1, 0.0, 1, 0, 8192);

-- 2) Tabla DemandasSentencias (misma estructura que DemandasCalificadas).
CREATE TABLE `JURISDB_CONSULTATIONIA`.`DemandasSentencias` (
`id` bigint NOT NULL AUTO_INCREMENT,
`nUnico` bigint DEFAULT NULL,
`userId` bigint DEFAULT NULL,
`model` char(50) DEFAULT NULL,
`roleSystem` text,
`temperature` decimal(3,1) DEFAULT NULL,
`fechaSend` datetime DEFAULT NULL,
`fechaResponse` datetime DEFAULT NULL,
`response` text,
`timeSeconds` double DEFAULT NULL,
`ConfigurationsId` int NOT NULL,
`status` tinyint DEFAULT NULL,
`anio` char(10) DEFAULT NULL,
`expNro` char(20) DEFAULT NULL,
`tipoExpediente` char(50) DEFAULT NULL,
`rutaCompleta` char(100) DEFAULT NULL,
`xformato` char(50) DEFAULT NULL,
`cclave` char(50) DEFAULT NULL,
`xnomInstancia` char(100) DEFAULT NULL,
`cubicacion` char(20) DEFAULT NULL,
`cinstancia` char(20) DEFAULT NULL,
`xdescEstado` char(50) DEFAULT NULL,
`cusuario` char(50) DEFAULT NULL,
`cmateria` char(20) DEFAULT NULL,
`cespecialidad` char(20) DEFAULT NULL,
`xdescUbicacion` char(100) DEFAULT NULL,
`xnombreArchivo` varchar(500) DEFAULT NULL,
`nincidente` char(20) DEFAULT NULL,
`xrutaArchivo` char(100) DEFAULT NULL,
`xdescMateria` char(100) DEFAULT NULL,
`finicio` datetime DEFAULT NULL,
`xdescJuez` char(200) DEFAULT NULL,
`xdescEspecialista` char(200) DEFAULT NULL,
`xdescDemandado` char(200) DEFAULT NULL,
`xdescDemandante` char(200) DEFAULT NULL,
PRIMARY KEY (`id`),
KEY `IdxUserId` (`userId`) /*!80000 INVISIBLE */,
KEY `IdxnUnico` (`nUnico`) /*!80000 INVISIBLE */,
KEY `IdxModel` (`model`) /*!80000 INVISIBLE */,
KEY `IdxFechaSend` (`fechaSend`) /*!80000 INVISIBLE */,
KEY `IdxFechaResponse` (`fechaResponse`) /*!80000 INVISIBLE */,
KEY `IdxStatus` (`status`) /*!80000 INVISIBLE */,
KEY `IdxAnio` (`anio`) /*!80000 INVISIBLE */,
KEY `IdxExpNro` (`expNro`) /*!80000 INVISIBLE */,
KEY `IdxTipoExp` (`tipoExpediente`) /*!80000 INVISIBLE */,
KEY `IdxCubicacion` (`cubicacion`) /*!80000 INVISIBLE */,
KEY `IdxCinstancia` (`cinstancia`),
KEY `IdxCusuario` (`cusuario`) /*!80000 INVISIBLE */,
KEY `IdxCmateria` (`cmateria`) /*!80000 INVISIBLE */,
KEY `IdxCespecialidad` (`cespecialidad`) /*!80000 INVISIBLE */,
KEY `IdxFinicio` (`finicio`),
KEY `fK_DemandasSentencias_Configurations` (`ConfigurationsId`),
CONSTRAINT `fK_DemandasSentencias_Configurations` FOREIGN KEY (`ConfigurationsId`) REFERENCES `JURISDB_CONSULTATIONIA`.`Configurations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
