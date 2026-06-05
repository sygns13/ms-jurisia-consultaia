ALTER TABLE `JURISDB_CONSULTATIONIA`.`Configurations`
    ADD COLUMN `maxOutputTokens` BIGINT NULL DEFAULT NULL AFTER `updUserId`;

insert into JURISDB_CONSULTATIONIA.Configurations(serviceCode, model, descripcion, roleSystem, promptDefault, maxMessages, temperature, activo, borrado, maxOutputTokens) values
    ('gemini_chat_1', 'gemini-3.1-pro-preview', 'Servicio de Consulta General de la IA con Gemini', 'Eres un abogado experto en derecho peruano con amplia experiencia en legislación, normativas y jurisprudencia en áreas como derecho penal, civil, constitucional, laboral y empresarial. Respondes preguntas jurídicos de manera clara y precisa, citando leyes y artículos relevantes del Código Civil, Código Penal, Constitución Política del Perú y demás normativas vigentes. No das consejos legales definitivos, pero brindas información detallada y explicas los procedimientos legales aplicables. Adicionalmente solo respondes consultas asociadas a temáticas legales, jurídicas o relacionados, en otros casos respondes amablemente que no atiendes esas clases de consultas. Cuando respondes lo haces de forma presisa sobre lo que te han consultado.',
     '',  30, 0.3, 1, 0, 8192);

insert into JURISDB_CONSULTATIONIA.Configurations(serviceCode, model, descripcion, roleSystem, promptDefault, maxMessages, temperature, activo, borrado, maxOutputTokens) values
    ('geminy_demanda_1', 'gemini-3.1-pro-preview', 'Servicio de Calificación de la Demanda', 'Eres un Asistente Judicial Virtual experto en derecho peruano con amplia experiencia en legislación, normativas y jurisprudencia en áreas como derecho penal, civil, constitucional, laboral y empresarial. Realizas calificaciones de Demandas en base a la normativa peruana, generas automáticamente el borrador de la resolución de calificación de demanda (Auto Admisorio o Resolución de Inadmisibilidad/Improcedencia) tras el análisis de una demanda ingresada. Por lo que tu respuesta debe de ser directamente el texto de la resolución de calificación de demanda.',
     'Por favor califica la demanda, tu respuesta debe de contener solo el borrador de la resolución judicial',  1, 0.0, 1, 0, 8192);


CREATE TABLE `JURISDB_CONSULTATIONIA`.`GeminiChats` (
`id` bigint NOT NULL AUTO_INCREMENT,
`userId` bigint DEFAULT NULL,
`model` char(50) DEFAULT NULL,
`roleSystem` text,
`prompt` text,
`temperature` decimal(3,1) DEFAULT NULL,
`fechaSend` datetime DEFAULT NULL,
`fechaResponse` datetime DEFAULT NULL,
`response` text,
`timeSeconds` double DEFAULT NULL,
`ConfigurationsId` int NOT NULL,
`sessionUID` char(50) NOT NULL,
`status` tinyint DEFAULT NULL,
`hasFiles` tinyint DEFAULT '0',
PRIMARY KEY (`id`),
KEY `IdxUserId` (`userId`) /*!80000 INVISIBLE */,
KEY `IdxModel` (`model`) /*!80000 INVISIBLE */,
KEY `IdxFechaSend` (`fechaSend`) /*!80000 INVISIBLE */,
KEY `IdxFechaResponse` (`fechaResponse`) /*!80000 INVISIBLE */,
KEY `IdxSessionUID` (`sessionUID`) /*!80000 INVISIBLE */,
KEY `idxhasFiles` (`hasFiles`),
KEY `fK_GeminiChats_Configurations` (`ConfigurationsId`),
CONSTRAINT `fK_GeminiChats_Configurations` FOREIGN KEY (`ConfigurationsId`) REFERENCES `JURISDB_CONSULTATIONIA`.`Configurations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `JURISDB_CONSULTATIONIA`.`DemandasCalificadas` (
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
KEY `fK_DemandasCalificadas_Configurations` (`ConfigurationsId`),
CONSTRAINT `fK_DemandasCalificadas_Configurations` FOREIGN KEY (`ConfigurationsId`) REFERENCES `JURISDB_CONSULTATIONIA`.`Configurations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;