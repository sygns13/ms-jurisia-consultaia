# Informe Técnico — Calificación de Demandas con IA Generativa (Gemini) y RAG Orquestado

**Microservicio:** `ms-jurisia-consultaia`
**Sistema:** Sistema Judicial IA — Corte Superior de Áncash, Poder Judicial del Perú
**Ámbito del documento:** Implementación de los endpoints de calificación de demandas (entorno de prueba y entorno de producción) basados en Google Vertex AI (modelos Gemini), recuperación aumentada por generación (RAG) sobre un índice vectorial, y las optimizaciones de costo y latencia aplicadas.

> Este documento describe, paso a paso y sin resumir, todo el trabajo de implementación, diagnóstico y optimización realizado. Está pensado como insumo para un informe técnico formal, por lo que se detallan los términos y decisiones de arquitectura involucradas.

---

## 1. Resumen ejecutivo

Se implementó, dentro del microservicio `ms-jurisia-consultaia`, un flujo de **calificación automática de demandas judiciales** que toma el archivo PDF de una demanda y genera el borrador de la **resolución de calificación** (Auto Admisorio, Resolución de Inadmisibilidad o Resolución de Improcedencia), apoyándose en:

1. **Modelos generativos Gemini** de Google Vertex AI para la comprensión del documento y la redacción de la resolución.
2. Un mecanismo de **RAG (Retrieval-Augmented Generation) orquestado**, que recupera la normativa procesal peruana pertinente desde un **índice de embeddings (Vertex AI Vector Search)** y la inyecta como contexto legal estricto en la generación final.

El flujo se materializó en dos puntos del microservicio:

- Un **controlador de prueba** (`DemandaTestControllerV2`), donde toda la lógica reside de forma directa para facilitar la experimentación.
- El **servicio de producción** (`GeminiServiceImpl`), respaldo de los dos endpoints oficiales expuestos por `ServiceGeminiController`, que conserva intactos los flujos previamente implementados (obtención del PDF por FTP, persistencia en base de datos y publicación en Kafka).

Adicionalmente se ejecutó un ciclo de **optimización de costo y tiempo de ejecución**, que incluyó: la migración del mecanismo de recuperación desde *Vertex AI Search (Data Store)* hacia *Vector Search* con búsqueda vectorial explícita, el uso de un modelo *Flash* para la fase de extracción, el control del presupuesto de razonamiento (*thinking budget*), el cacheo de credenciales, la instrumentación de tiempos por fase, y —como mayor logro frente al cuello de botella del proxy institucional— la estrategia de **subir el PDF una sola vez a Google Cloud Storage y referenciarlo por URI** en las llamadas a Gemini.

El resultado medido en el entorno de producción (con proxy) fue una reducción del tiempo total de procesamiento de **195.63 segundos a 110.13 segundos** (aproximadamente **−44 %**), aislando además todo el costo remanente del proxy en una única operación de subida.

---

## 2. Contexto y alcance

### 2.1. El microservicio `ms-jurisia-consultaia`

Es el microservicio oficial (en Java) de consulta legal con inteligencia artificial para el Poder Judicial del Perú. Su pila tecnológica es:

- **Framework:** Spring Boot 3.4.3
- **Lenguaje / runtime:** Java 21
- **Persistencia relacional:** MySQL (base `JURISDB_CONSULTATIONIA`)
- **Caché / almacén clave-valor:** Redis
- **Mensajería:** Apache Kafka
- **Puerto HTTP:** 8011
- **Documentación de API:** Springdoc OpenAPI (Swagger UI en `/swagger-ui.html`)

El paquete base del código es `pj.gob.pe.consultaia`.

### 2.2. Objetivo funcional del flujo de calificación

Dada una demanda judicial en formato PDF, el sistema debe producir automáticamente el **borrador de la resolución de calificación de demanda**. La calificación es el acto procesal por el cual el juez evalúa si una demanda cumple los requisitos de admisibilidad y procedencia, resultando en uno de tres posibles pronunciamientos:

- **Auto Admisorio:** la demanda reúne los requisitos y se admite a trámite.
- **Resolución de Inadmisibilidad:** existen defectos subsanables; se concede un plazo para subsanar.
- **Resolución de Improcedencia:** existen defectos insubsanables; se rechaza la demanda.

La generación debe basarse **estrictamente** en la normativa procesal peruana aplicable, la cual se recupera dinámicamente mediante RAG.

### 2.3. Alcance del trabajo

El requerimiento consistió en **trasladar** el flujo de RAG y calificación que se había prototipado en el controlador de prueba `DemandaTestControllerV2` hacia el servicio de producción que respaldan los dos endpoints de `ServiceGeminiController`, modificando **únicamente** la lógica de RAG y de calificación, y **sin alterar** los flujos ya implementados:

- La obtención del PDF de la demanda desde el servidor FTP.
- Los datos que se registran en la base de datos (entidad `DemandasCalificadas`).
- Los datos que se publican en Kafka.

La nueva implementación deja de usar el *Data Store* de Vertex AI Search y pasa a usar dos identificadores nuevos de configuración: `indexEndpointId` y `deployedIndexId`.

---

## 3. Arquitectura general del flujo

### 3.1. Componentes principales

| Componente | Tipo | Responsabilidad |
|------------|------|-----------------|
| `ServiceGeminiController` | Controlador REST (`@RestController`, `@RequestMapping("/v1/gemini")`) | Expone los dos endpoints de calificación de producción. |
| `GeminiService` / `GeminiServiceImpl` | Interfaz + implementación de servicio | Orquesta la calificación de producción: sesión, configuración, persistencia, FTP, RAG y generación. |
| `DemandaTestControllerV2` | Controlador REST de prueba | Replica el flujo RAG completo de forma autocontenida para experimentación. |
| `ChunkStoreService` / `ChunkStoreServiceImpl` | Servicio de negocio | Almacena en Redis los *chunks* normativos (textos reales) y reconstruye el contexto legal a partir de los identificadores devueltos por la búsqueda vectorial. |
| `GcsStorageService` / `GcsStorageServiceImpl` | Servicio externo | Sube el PDF a Google Cloud Storage y lo borra al finalizar (estrategia de optimización del proxy). |
| `ConfigProperties` | Clase de configuración (`@Configuration`) | Mapea las propiedades del `application.yml` a campos tipados inyectables. |
| `FtpService` | Servicio externo | Descarga el PDF de la demanda desde el FTP (flujo preexistente, no modificado). |
| `SecurityService` | Servicio externo | Valida la sesión del usuario contra la API de seguridad (flujo preexistente, no modificado). |

### 3.2. Diagrama de flujo (descripción textual)

El flujo de producción (`GeminiServiceImpl.calificarDemanda`) procede en el siguiente orden:

1. **Validación de sesión:** se valida el `SessionId` recibido en la cabecera HTTP contra la API de seguridad (`http://localhost:8010`).
2. **Carga de configuración:** se obtiene de la base de datos el registro de `Configurations` correspondiente al código de servicio `geminy_demanda_1` (modelo, *system instruction*, *prompt* por defecto, temperatura, tope de tokens de salida).
3. **Registro inicial en BD:** se crea el registro `DemandasCalificadas` en estado *iniciada*, con todos los metadatos del expediente.
4. **Descarga del PDF por FTP:** se recupera el archivo de la demanda.
5. **Invocación de la IA (RAG orquestado):** se ejecuta el flujo de cuatro fases (detallado en la sección 4) que produce el texto de la resolución.
6. **Actualización en BD:** se persiste la respuesta, el tiempo y el estado *exitosa*.
7. **(Publicación en Kafka):** prevista en el código (actualmente comentada en el flujo); su contrato no se modificó.

El endpoint de variante DOCX (`calificarDemandaDocx`) ejecuta el mismo flujo y luego convierte el texto resultante a un documento Word descargable mediante `DocxGeneratorUtil.textToDocx`.

---

## 4. El flujo RAG Orquestado (cuatro fases)

El término **RAG Orquestado** se refiere a que la recuperación de contexto no se delega a una herramienta gestionada de Vertex (como el *Data Store*), sino que el propio servicio **orquesta explícitamente** cada paso de la recuperación mediante llamadas independientes. Esto otorga control total sobre el modelo de embeddings, el índice consultado, el número de vecinos recuperados y la construcción del contexto.

El flujo consta de cuatro fases secuenciales (cada una depende del resultado de la anterior):

### 4.1. Fase 1 — Extracción de intención (conceptos jurídicos clave)

- **Qué hace:** se envía el PDF de la demanda a un modelo Gemini junto con una instrucción de extracción, para que devuelva **únicamente** una lista de palabras clave: los conceptos jurídicos procesales involucrados, el tipo de proceso y las posibles omisiones de forma.
- **Prompt de extracción (fijo, interno del mecanismo RAG):**
  > "Lee esta demanda adjunta. Extrae ÚNICAMENTE una lista de los conceptos jurídicos procesales involucrados, el tipo de proceso, y las posibles omisiones de forma. Responde solo con palabras clave separadas por comas, sin explicaciones."
- **Propósito técnico:** esta fase actúa como un mecanismo de **compresión semántica**. El modelo de embeddings tiene un límite de tokens de entrada (no se puede vectorizar una demanda completa de muchas páginas), por lo que se usa un modelo generativo para destilar la demanda en un conjunto reducido de términos representativos que sí son vectorizables.
- **Configuración del modelo:** temperatura `0.0` (determinismo máximo) y, tras la optimización, modelo *Flash* con **razonamiento desactivado** (ver sección 8).
- **SDK:** se invoca con la clase `GenerativeModel` del SDK de Vertex AI (`com.google.cloud.vertexai`), sobre transporte REST.

### 4.2. Fase 2 — Generación de embeddings (vía REST cruda)

- **Qué hace:** convierte la cadena de términos clave de la Fase 1 en un **vector de embedding** (representación numérica densa del significado del texto).
- **Modelo de embeddings:** `text-multilingual-embedding-002` (modelo multilingüe de Google, apropiado para texto en español jurídico).
- **Endpoint REST:**
  `https://{location}-aiplatform.googleapis.com/v1/projects/{projectId}/locations/{location}/publishers/google/models/{embeddingModel}:predict`
- **Cuerpo de la petición:** un objeto `instances` con dos campos:
  - `content`: el texto a vectorizar.
  - `task_type`: `"RETRIEVAL_QUERY"`, que indica al modelo que el embedding es del lado de la **consulta** (optimizado para recuperación, distinto del embedding del lado del documento indexado, que sería `RETRIEVAL_DOCUMENT`).
- **Respuesta:** se extrae el arreglo numérico de `predictions[0].embeddings.values`.

### 4.3. Fase 3 — Búsqueda vectorial (`findNeighbors`, vía REST cruda)

- **Qué hace:** consulta el **índice de Vector Search** con el vector de la Fase 2 para recuperar los identificadores de los fragmentos normativos (artículos/incisos) **más similares semánticamente** a la consulta.
- **Recurso del Index Endpoint:**
  `projects/{projectId}/locations/{location}/indexEndpoints/{indexEndpointId}`
- **Endpoint REST:** `{dominio}/v1/{indexEndpointResource}:findNeighbors`, donde `{dominio}` es el **dominio público dedicado** del Index Endpoint (ver sección 8.1 sobre la resolución de dominio).
- **Cuerpo de la petición:**
  - `deployedIndexId`: identificador del índice desplegado dentro del endpoint.
  - `queries[].datapoint.featureVector`: el vector de consulta.
  - `queries[].neighborCount`: cantidad de vecinos (artículos) a recuperar.
- **Respuesta:** se recorre `nearestNeighbors[].neighbors[].datapoint.datapointId` para construir la lista ordenada (por relevancia) de identificadores de *chunks* normativos.

### 4.4. Fase 4 — Generación final (resolución de calificación)

- **Qué hace:** redacta el borrador de la resolución de calificación combinando: (a) las instrucciones del juzgado, (b) la normativa legal recuperada, y (c) el PDF completo de la demanda.
- **Reconstrucción del contexto legal:** los identificadores de la Fase 3 se pasan a `ChunkStoreService.construirContextoLegal(ids)`, que recupera de Redis el **texto real** de cada fragmento normativo (junto con su cita formateada) y los concatena en bloque, respetando el orden de relevancia.
- **Modelo:** modelo Gemini *Pro* (de máxima capacidad), con la *system instruction*, el modelo, la temperatura y el tope de tokens de salida tomados de la base de datos en producción (decisión "la BD manda en Fase 4").
- **Prompt enriquecido (plantilla):**
  ```
  INSTRUCCIONES DEL JUZGADO:
  {promptDefault / instrucciones del usuario}

  NORMATIVA LEGAL ESTRICTA A APLICAR:
  {contexto legal recuperado de Redis}

  TAREA:
  Analiza el documento PDF adjunto basándote exclusivamente en la normativa
  proporcionada y redacta la resolución de calificación de demanda.
  ```
- **Resultado:** el texto de la resolución, devuelto como respuesta del flujo.

---

## 5. Migración de Vertex AI Search (Data Store) a Vector Search

### 5.1. Mecanismo anterior

La implementación previa de `GeminiServiceImpl` usaba la herramienta gestionada **Vertex AI Search** mediante el patrón de *Retrieval Tool*:

- Se construía un `VertexAISearch` apuntando a un *Data Store* (`dataStoreId: codigos-procesales-demo-1_...`).
- Se envolvía en un `Retrieval` y este en un `Tool`.
- El `GenerativeModel` recibía la herramienta (`withTools(...)`) y, en **una sola llamada**, el propio Gemini decidía internamente qué recuperar del Data Store y generaba la respuesta.

### 5.2. Mecanismo nuevo

La nueva implementación **elimina el Data Store** y orquesta la recuperación explícitamente con las cuatro fases descritas. Las ventajas técnicas son:

- **Control total** sobre el modelo de embeddings, el índice, el número de vecinos y el formato del contexto.
- **Trazabilidad**: se conocen exactamente los identificadores normativos recuperados.
- **Desacoplamiento** del texto normativo respecto del índice: el índice almacena solo los embeddings y los identificadores; el **texto real** de cada fragmento vive en Redis (precargado desde archivos `chunks/*.jsonl`). Esto mantiene el índice liviano.

### 5.3. Nuevos identificadores de configuración

| Identificador | Descripción |
|---------------|-------------|
| `indexEndpointId` | Identificador del *Index Endpoint* de Vector Search (la infraestructura pública que sirve consultas). Valor: `4248471148284608512`. |
| `deployedIndexId` | Identificador del índice **desplegado** dentro del endpoint. Valor: `pj_normativa_deploy_1_1780024396444`. |

Ambos se externalizaron al `application.yml` y se mapearon en `ConfigProperties`.

---

## 6. Decisiones técnicas de integración con Vertex AI

### 6.1. Transporte REST obligatorio (no gRPC)

El SDK de Vertex AI (`com.google.cloud.vertexai`, versión **1.48.0**) se configuró para usar **transporte REST** (`Transport.REST`), no gRPC. La razón es que con la *location* `global` (requerida para el modelo Gemini configurado) el transporte gRPC no funciona; el endpoint debe ser `aiplatform.googleapis.com` (sin prefijo de región).

### 6.2. Configuración del cliente de predicción

- Se construye `PredictionServiceSettings` con `newHttpJsonBuilder()` (transporte HTTP/JSON).
- Se fija el endpoint `aiplatform.googleapis.com:443` y el proveedor de credenciales (`FixedCredentialsProvider`).
- Se establecen **timeouts amplios** dadas las latencias de generación: 5 minutos de *RPC timeout* inicial y máximo, y 10 minutos de *total timeout*, mediante `RetrySettings`.
- Cuando hay proxy, se inyecta el `NetHttpTransport` con proxy al `TransportChannelProvider` (`defaultHttpJsonTransportProviderBuilder().setHttpTransport(...)`).

### 6.3. Particularidades del `VertexAI.Builder` v1.48.0

- La versión 1.48.0 del builder **no** dispone del método `setPredictionServiceSettings()`. En su lugar se usa `setPredictionClientSupplier(...)`, al que se le pasa una lambda que crea el `PredictionServiceClient` a partir de los `PredictionServiceSettings` construidos.
- `GenerativeModel.withGenerationConfig(...)` es **inmutable**: retorna una nueva instancia del modelo con la configuración aplicada, no modifica la instancia existente.
- Se debe pasar `.setCredentials(credentials)` al builder para evitar un error de *Application Default Credentials* (ADC) al cerrar el recurso (`close()`).
- El `VertexAI` se usa dentro de un *try-with-resources* para garantizar su cierre.

### 6.4. Embeddings y Vector Search por REST cruda (no GAPIC)

Las operaciones de **embeddings** y **búsqueda vectorial** se invocan mediante **REST cruda** (peticiones HTTP construidas manualmente con `NetHttpTransport`, `HttpRequestFactory`, `GenericUrl` y `ByteArrayContent`), y **no** mediante las clases GAPIC generadas. La razón técnica es que, en la librería `google-cloud-aiplatform`, las clases `MatchServiceSettings` y `PredictionServiceSettings` del paquete `com.google.cloud.aiplatform.v1` **solo exponen transporte gRPC** (no existen `newHttpJsonBuilder()` ni `defaultHttpJsonTransportProviderBuilder()`). La REST cruda, además, **reutiliza el mismo `NetHttpTransport` con proxy** que usa el SDK de Gemini, manteniendo un manejo de proxy uniforme para la red del Poder Judicial.

Las utilidades REST comunes (`ejecutarPost`, `ejecutarGet`, `ejecutar`) fijan la cabecera `Authorization: Bearer {token}`, *connect timeout* de 60 s, *read timeout* de 120 s, y `setThrowExceptionOnExecuteError(false)` para manejar manualmente los códigos de error (lanzando `IOException` con el cuerpo de la respuesta cuando el estado es ≥ 300).

---

## 7. Componentes auxiliares

### 7.1. `ChunkStoreService` — almacén de *chunks* normativos en Redis

- **Carga:** al arrancar el contexto de Spring (`ApplicationReadyEvent`), lee todos los archivos `classpath*:chunks/*.jsonl` y los carga en un *hash* de Redis. Cada línea JSONL contiene un `id`, un `content` (texto real del fragmento) y un `structData` (metadatos: código, título, número de artículo, fuente PDF, página).
- **Clave del hash:** `{prefijo}:chunks:normativa` (prefijo configurable, por defecto `jurisia_consultationia`).
- **Sin TTL:** el hash no expira; los datos persisten indefinidamente. La recarga al arranque es idempotente y de bajo costo.
- **Reconstrucción del contexto:** `construirContextoLegal(List<String> ids)` toma los identificadores devueltos por la búsqueda vectorial, elimina duplicados conservando el orden de relevancia, recupera los JSON de Redis (`multiGet`), y formatea cada fragmento como un bloque legible con su **cita** (código · título · número de artículo, y fuente/página) seguido del **contenido**. Incluye un *fallback*: si Redis no tuviera los datos al momento de la consulta, los recarga en ese instante.

### 7.2. `ConfigProperties` — mapeo de configuración

Clase `@Configuration` con `@Getter`/`@Setter` que mapea, mediante anotaciones `@Value`, todas las propiedades del `application.yml` a campos tipados. Se ampliaron sus campos para soportar los nuevos parámetros del RAG orquestado, los modelos, el dominio del endpoint y el bucket de GCS (ver sección 9).

### 7.3. `GcsStorageService` — almacenamiento temporal en Google Cloud Storage

Servicio externo introducido como parte de la optimización del proxy (sección 8.5). Encapsula dos operaciones contra la **JSON API de Cloud Storage**, implementadas por REST cruda (sin agregar la dependencia `google-cloud-storage`):

- `subir(byte[] contenido, String contentType)`: sube los bytes a un objeto único `{prefijo}/{uuid}.{ext}` bajo el bucket configurado, mediante `POST .../upload/storage/v1/b/{bucket}/o?uploadType=media&name={objeto}`. Devuelve el URI `gs://{bucket}/{objeto}`.
- `borrar(String gsUri)`: elimina el objeto mediante `DELETE .../storage/v1/b/{bucket}/o/{objeto}`. Es idempotente (un HTTP 404 se trata como éxito).

Reutiliza las credenciales cacheadas del service account y el transporte con proxy. El *read timeout* de subida se fijó en 5 minutos para tolerar la lentitud del proxy.

---

## 8. Optimizaciones implementadas

Esta sección documenta, una por una, las optimizaciones aplicadas. Todas se diseñaron con la restricción explícita de **no reducir la capacidad de cómputo ni de análisis** de la calificación final (Fase 4).

### 8.1. Resolución híbrida del dominio del Index Endpoint

**Problema detectado:** la Fase 3 (búsqueda vectorial) requiere conocer el **dominio público dedicado** del Index Endpoint (`publicEndpointDomainName`), porque en endpoints **públicos** la operación `findNeighbors` debe invocarse contra ese dominio y no contra el endpoint regional estándar. La implementación inicial obtenía ese dominio con un **GET de metadata en cada calificación**, pese a que el dominio es prácticamente estático (solo cambia si se redespliega el endpoint).

**Solución (estrategia híbrida):**

1. Si la propiedad `vectorSearchPublicDomain` está configurada, se usa directamente (cero llamadas de red).
2. Si no, se resuelve por metadata **una sola vez** y se **cachea en memoria** (campo `volatile` con *double-checked locking*), reutilizándose en todas las calificaciones posteriores.

Se añadió un método `normalizarDominio` que antepone `https://` si el valor configurado viene sin esquema.

**Obtención del valor real:** usando el JSON del service account presente en la configuración, se autenticó contra GCP y se consultó la metadata del Index Endpoint, obteniendo:

- `displayName`: **extremo-leyes**
- `publicEndpointDomainName`: **`1010368964.us-central1-352687524003.vdb.vertexai.goog`**
- `deployedIndexes`: `['pj_normativa_deploy_1_1780024396444']` (coincide con el `deployedIndexId` configurado).

Este valor se registró en la propiedad `vectorSearchPublicDomain`, eliminando incluso el GET de la primera calificación.

**Impacto:** de **3** llamadas REST por calificación (embedding + GET de dominio + findNeighbors) se pasó a **2** (embedding + findNeighbors).

### 8.2. Modelo *Flash* para la Fase 1 (extracción)

**Análisis:** la Fase 1 es un paso de **recuperación**, no de análisis final. Extraer palabras clave de la demanda no requiere el modelo más caro y lento (*Pro*). Un modelo **Flash** (más rápido y económico) realiza esa extracción con calidad suficiente.

**Cambio:** el modelo de la Fase 1 se parametrizó (`extractionModel`, p. ej. `gemini-3.5-flash`), mientras que la Fase 4 conserva el modelo *Pro* (`gemini-3.1-pro-preview`), que en producción proviene de la base de datos. De este modo se reduce costo y tiempo del paso de *routing* **sin tocar** la calidad del análisis final.

### 8.3. Control del presupuesto de razonamiento (*thinking budget*)

**Análisis:** los modelos Gemini de la familia 3.x son modelos de **razonamiento**: por defecto consumen tokens (y tiempo) "pensando" antes de responder. En la Fase 1 (extracción simple de palabras clave) ese razonamiento es innecesario.

**Cambio:** en la Fase 1 se configuró `ThinkingConfig` con `thinkingBudget = 0` e `includeThoughts = false`, desactivando el razonamiento y acelerando/abaratando la extracción. La Fase 4 conserva su comportamiento por defecto (razonamiento completo), para no afectar la calidad de la redacción.

### 8.4. Cacheo de credenciales (reutilización del token OAuth)

**Análisis:** cada calificación construía las credenciales desde el JSON del service account, firmaba un JWT y solicitaba un token OAuth nuevo (ida y vuelta de red).

**Cambio:** las `GoogleCredentials` se cachean a nivel de servicio/controlador (campo `volatile` con *double-checked locking*). El token OAuth (TTL ~1 hora) se reutiliza entre calificaciones; `refreshIfExpired()` solo realiza una llamada de red cuando el token efectivamente expiró. Esto evita el *round-trip* de firma JWT + intercambio de token en cada petición.

### 8.5. Instrumentación de tiempos por fase

Para medir con datos reales el peso de cada fase, se añadió un *logging* estructurado que registra la duración de cada fase y el total. Se añadió el método utilitario `seg(...)`, que formatea el lapso entre dos marcas de `System.nanoTime()` en segundos con dos decimales. Ejemplo de línea de log:

```
[Calificación tiempos] GCS-upload=55.98s | Fase1(extracción gemini-3.5-flash)=8.02s |
   Fase2(embedding)=1.21s | Fase3(vectorSearch)=1.42s | Fase4(redacción gemini-3.1-pro-preview)=43.47s | Total IA=110.13s
```

También se añadió un *log* de **diagnóstico del tamaño del PDF** (en bytes, MB, y estimación de bytes en el cable considerando la inflación de base64 y los dos envíos).

### 8.6. Estrategia de Google Cloud Storage (subir una vez, referenciar por URI)

**Esta fue la optimización de mayor impacto frente al cuello de botella del proxy.** Se describe en detalle en la sección 10, tras el diagnóstico que la motivó.

---

## 9. Configuración (`application.yml` y `ConfigProperties`)

Bajo el nodo `gcp:` del `application.yml` se mantienen y/o añadieron las siguientes propiedades:

| Propiedad (`gcp.*`) | Valor | Propósito |
|---------------------|-------|-----------|
| `projectId` | `apubot-v1` | Proyecto de GCP. |
| `locationGlobal` | `global` | *Location* del modelo Gemini. |
| `scoped` | `https://www.googleapis.com/auth/cloud-platform` | *Scope* OAuth de las credenciales. |
| `endpoint` | `aiplatform.googleapis.com:443` | Endpoint del `PredictionServiceSettings`. |
| `endpointAPI` | `aiplatform.googleapis.com` | API endpoint del `VertexAI.Builder`. |
| `vectorSearchLocation` | `us-central1` | Región donde se creó el índice de Vector Search. |
| `extractionModel` | `gemini-3.5-flash` | Modelo Gemini de la Fase 1 (extracción). |
| `embeddingModel` | `text-multilingual-embedding-002` | Modelo de embeddings de la Fase 2. |
| `neighborCount` | `20` | Cantidad de artículos a recuperar en la Fase 3. |
| `indexEndpointId` | `4248471148284608512` | Index Endpoint de Vector Search. |
| `deployedIndexId` | `pj_normativa_deploy_1_1780024396444` | Índice desplegado. |
| `vectorSearchPublicDomain` | `1010368964.us-central1-352687524003.vdb.vertexai.goog` | Dominio público del Index Endpoint (evita el GET de metadata). |
| `gcsBucket` | `pj_demandas_calificacion` | Bucket de GCS para el PDF temporal. |
| `gcsObjectPrefix` | `demandas` | Prefijo (carpeta lógica) de los objetos en el bucket. |
| `credentials.content` | JSON | Credenciales IAM del service account (`spring-boot-vertex-ai@apubot-v1.iam.gserviceaccount.com`). |

> Nota: las propiedades `dataStoreId` y `datastorePath`, asociadas al mecanismo anterior de Vertex AI Search, se conservaron en la configuración para no romper nada, aunque dejaron de utilizarse en el nuevo flujo.

Cada propiedad se mapeó a un campo de `ConfigProperties` mediante `@Value("${gcp.<propiedad>}")`. La propiedad `vectorSearchPublicDomain` se mapeó con valor por defecto vacío (`@Value("${gcp.vectorSearchPublicDomain:}")`) para que sea opcional.

### 9.1. Diferencias intencionales entre el entorno de prueba y el de producción

| Aspecto | Test (`DemandaTestControllerV2`) | Producción (`GeminiServiceImpl`) |
|---------|----------------------------------|----------------------------------|
| Modelo de la Fase 4 | Constante en el controlador (`gemini-3.1-pro-preview`). | Tomado de la base de datos (`configurations.getModel()`). |
| *System instruction* y *prompt* | Cadenas fijas en el código. | Tomados de la BD (`roleSystem` y `promptDefault`). |
| `neighborCount` | Ajustado a 15 durante la experimentación. | 20 (propiedad `gcp.neighborCount`). |
| Configuración (modelos, dominio, etc.) | Campos constantes en la clase. | Externalizada en `application.yml` + `ConfigProperties`. |
| Entrada del PDF | `MultipartFile` subido directamente al endpoint. | Descargado por FTP dentro del flujo. |

---

## 10. Diagnóstico de rendimiento y la estrategia de Google Cloud Storage

### 10.1. Síntoma

El tiempo de procesamiento en producción superaba el minuto, siendo significativamente mayor que en el entorno local de prueba. Se compararon los tiempos por fase entre el entorno local (conexión directa a Internet) y el servidor de producción (egress a través del **proxy institucional del Poder Judicial**).

### 10.2. Mediciones comparativas (antes de la estrategia GCS)

| Fase | Local (directo) | Servidor (proxy) | Δ atribuible al proxy |
|------|----------------:|-----------------:|----------------------:|
| Fase 1 — Flash (extracción) | 8.38 s | 77.19 s | +68.8 s |
| Fase 2 — Embeddings (REST) | 1.27 s | 1.31 s | +0.0 s |
| Fase 3 — Vector Search (REST) | 1.63 s | 1.48 s | −0.2 s |
| Fase 4 — Pro (redacción) | 47.25 s | 115.65 s | +68.4 s |
| **Total** | **58.95 s** | **195.63 s** | **+136.7 s** |

### 10.3. Análisis de causa raíz

El patrón es concluyente:

- Las **Fases 2 y 3** (REST cruda con payloads diminutos: un texto de palabras clave y un vector) tardan **lo mismo** con o sin proxy. Por lo tanto, el proxy **no** añade latencia por el simple hecho de conectar.
- Las **Fases 1 y 4** (llamadas a Gemini) suman **+68 segundos cada una**, de forma casi idéntica.

Lo que distingue a las Fases 1 y 4 de las Fases 2 y 3 es que **ambas suben el PDF completo**: el SDK incrusta los bytes del documento en **base64** dentro del cuerpo de la petición (`PartMaker.fromMimeTypeAndData(mime, byte[])` produce un campo `inlineData`). Las Fases 2 y 3 envían payloads minúsculos.

**Conclusión:** el proxy es muy lento subiendo el PDF, y el flujo **lo subía dos veces** (una en la Fase 1 y otra en la Fase 4), generando ~137 segundos de "impuesto de proxy".

### 10.4. Cuantificación del throughput del proxy

Con un PDF de **10 MB** (confirmado en pruebas), su representación inline en base64 es de **~13.3 MB** por envío. Subir 13.3 MB en ~68 s equivale a un *throughput* de subida de **~1.5 Mbit/s** (~195 KB/s). Dos envíos suman ~26.6 MB a esa velocidad.

Para respaldar este dato con una medición reproducible e independiente del modelo, se elaboró un **script de bench de throughput** (`diagnostics/proxy_throughput_bench.py`) que:

- Lee las credenciales, la configuración del proxy y los parámetros desde el `application.yml`.
- Envía al endpoint de predicción payloads de tamaño creciente (1, 3, 6, 9 MB) y mide el tiempo hasta la respuesta (que, para payloads grandes, está dominado por la subida).
- Calcula por **regresión lineal** el ancho de banda sostenido (pendiente, en MB/s) y la latencia fija (intercepto), y proyecta el tiempo de subida para los 13.3 MB del PDF.
- Permite ejecutarse en modo **directo** (`--no-proxy`) y **por proxy** (`--proxy http://host:puerto`), para comparar ambos caminos en el mismo servidor.

### 10.5. La estrategia de Google Cloud Storage

**Idea:** dado que el cuello de botella es subir el PDF por el proxy y que se hace dos veces, la solución es **subir el PDF una sola vez** y que ambas fases lo **referencien por URI**, de modo que **Vertex lo lea desde Google Cloud Storage del lado del servidor** (dentro de la red de Google), sin retransmitir los bytes pesados por el proxy en cada fase.

**Detalle técnico clave del SDK:** se verificó, inspeccionando el código fuente de `PartMaker` (versión 1.48.0), que `fromMimeTypeAndData(String mimeType, Object partData)` se comporta según el tipo del segundo argumento:

- Si es `byte[]` o `ByteString` → genera un `Part` con `inlineData` (el documento viaja incrustado).
- Si es `String` o `URI` → genera un `Part` con **`fileData`** y `fileUri` (el documento se referencia por su URI `gs://...`).

Por lo tanto, basta con pasar el URI `gs://...` en lugar de los bytes para que el SDK lo referencie en vez de incrustarlo.

**Implementación:**

1. Se creó el servicio `GcsStorageService` / `GcsStorageServiceImpl` (REST cruda contra la JSON API de Storage).
2. En ambos puntos (test y producción) se subió el PDF una vez (`gcsStorageService.subir(pdfBytes, "application/pdf")`), obteniendo el URI `gs://pj_demandas_calificacion/demandas/{uuid}.pdf`.
3. Las Fases 1 y 4 construyen el `documentPart` con `PartMaker.fromMimeTypeAndData("application/pdf", gsUri)`.
4. El objeto se **borra en un bloque `finally`** (best-effort, registrando un *warning* si el borrado falla), garantizando que no queden objetos huérfanos en el bucket.
5. Se añadió la métrica `GCS-upload=Xs` al log de tiempos.

**Verificación de permisos:** antes de probar el flujo, se ejecutó un ciclo real **CREATE → READ → DELETE** contra el bucket usando el service account, confirmando los tres permisos necesarios:

| Operación | Permiso IAM | Resultado |
|-----------|-------------|-----------|
| CREATE (subir) | `storage.objects.create` | HTTP 200 ✔ |
| READ (lo que hace Vertex al leer el `gs://`) | `storage.objects.get` | HTTP 200 ✔ |
| DELETE (borrar) | `storage.objects.delete` | HTTP 204 ✔ |

El service account `spring-boot-vertex-ai@apubot-v1.iam.gserviceaccount.com` posee los tres permisos sobre el bucket `pj_demandas_calificacion`, por lo que no fue necesario modificar IAM ni añadir reglas de ciclo de vida.

### 10.6. Resultados después de la estrategia GCS

| Fase | Antes (proxy) | Después (proxy + GCS) | Local (GCS) |
|------|-------------:|----------------------:|------------:|
| GCS-upload | — | 55.98 s | 2.81 s |
| Fase 1 (Flash) | 77.19 s | 8.02 s | 7.83 s |
| Fase 2 (embeddings) | 1.31 s | 1.21 s | 1.21 s |
| Fase 3 (vector search) | 1.48 s | 1.42 s | 0.44 s |
| Fase 4 (Pro) | 115.65 s | 43.47 s | 42.67 s |
| **Total** | **195.63 s** | **110.13 s** | **54.96 s** |

**Interpretación:**

- El total en producción se redujo de **195.63 s a 110.13 s** (**−44 %**).
- Las Fases 1 y 4 pasaron a ejecutarse a **velocidad de local** (8.02 vs 7.83 s; 43.47 vs 42.67 s): ya no retransmiten el PDF, solo envían el URI diminuto. El "impuesto de proxy" de esas dos fases **desapareció**.
- Todo el costo remanente del proxy quedó **aislado en una única operación de subida** (55.98 s). Subir ~10 MB en 55.98 s equivale a **~1.43 Mbit/s**, confirmando con un dato único e independiente la hipótesis del proxy lento.
- La diferencia restante entre servidor y local (110.13 − 54.96 ≈ 55 s) corresponde **íntegramente** a esa subida única; en todas las demás fases servidor y local son equivalentes.

### 10.7. Trabajo remanente identificado

El tiempo total restante (110 s) se compone de dos bloques:

1. **Subida a GCS (~56 s):** puro proxy (~1.43 Mbit/s). Su solución de raíz es de **infraestructura/red**: permitir egress directo a `*.googleapis.com` para el tráfico de Vertex/Storage, o mejorar el throughput del proxy. Esto llevaría el total a ~57 s (equiparable al entorno local). Se cuenta ahora con el dato exacto de throughput para escalar el requerimiento al equipo de red.
2. **Fase 4 (~43 s):** cómputo real del modelo *Pro* generando la resolución (hasta 8192 tokens de salida). Es el **piso irreducible** sin recortar la longitud/calidad de la resolución.

Como alternativa **a nivel de código** (no de infraestructura), se identificó la posibilidad de **comprimir/reducir la resolución (DPI) del PDF** antes de subirlo (un PDF de demanda de 10 MB suele ser un escaneo de alta resolución), lo que reduciría proporcionalmente el tiempo de subida. Esta opción es sensible a la capacidad (afecta la resolución de las imágenes que lee Gemini), por lo que requeriría validación previa de que no degrada la lectura del documento.

---

## 11. Entorno de compilación

Durante el trabajo se constató que la compilación del módulo requiere **JDK 21**. La variable `JAVA_HOME` del entorno apuntaba a un JDK 8 (Temurin 1.8), con el cual el arranque de Maven falla, porque el *build extension* del plugin GraalVM (`native-maven-plugin`) está compilado para una versión de clase superior (Java 11+) a la reconocida por Java 8 (`UnsupportedClassVersionError: class file version 55.0` frente a `52.0`).

La compilación se realizó fijando `JAVA_HOME` a un JDK 21 (Temurin 21.0.11) para esa ejecución. Para builds por consola se recomienda apuntar `JAVA_HOME` a un JDK 21 (el IDE ya usa su propio JDK configurado).

---

## 12. Inventario de cambios

### 12.1. Archivos creados

| Archivo | Propósito |
|---------|-----------|
| `service/externals/GcsStorageService.java` | Interfaz del almacenamiento temporal en GCS. |
| `service/externals/impl/GcsStorageServiceImpl.java` | Implementación REST cruda (upload/delete) contra la JSON API de Storage. |
| `diagnostics/proxy_throughput_bench.py` | Script de bench de throughput del proxy (diagnóstico para el equipo de red). |
| `docs/INFORME_TECNICO_Calificacion_Demandas_Gemini_RAG.md` | Este documento. |

### 12.2. Archivos modificados

| Archivo | Cambios |
|---------|---------|
| `controller/ServiceGeminiController.java` | (Sin cambios de lógica; expone los dos endpoints que delegan en `GeminiService`.) |
| `service/business/impl/GeminiServiceImpl.java` | Reemplazo del mecanismo de RAG: de *Data Store* a RAG orquestado de 4 fases; modelo Flash + thinking 0 en Fase 1; cache de credenciales; instrumentación de tiempos; integración de GCS (subir/URI/borrar); log de tamaño de PDF; resolución híbrida del dominio. |
| `controller/test/DemandaTestControllerV2.java` | Mismas optimizaciones aplicadas al controlador de prueba (resolución híbrida del dominio, Flash + thinking 0, cache de credenciales, instrumentación, GCS, log de tamaño de PDF). |
| `configuration/ConfigProperties.java` | Nuevos campos: `vectorSearchLocation`, `extractionModel`, `embeddingModel`, `neighborCount`, `indexEndpointId`, `deployedIndexId`, `vectorSearchPublicDomain`, `gcsBucket`, `gcsObjectPrefix`. |
| `resources/application.yml` | Nuevas propiedades bajo `gcp:` (RAG orquestado, modelos, dominio del endpoint, bucket de GCS). |

### 12.3. Endpoints involucrados

| Endpoint | Método | Controlador | Descripción |
|----------|--------|-------------|-------------|
| `/v1/gemini/calificar-demanda` | POST | `ServiceGeminiController` | Recupera el PDF por FTP y genera la calificación (respuesta JSON `ApiResponse<ResponseCalificacionDemanda>`). |
| `/v1/gemini/calificar-demanda-docx` | POST | `ServiceGeminiController` | Mismo flujo, retorna el resultado como archivo `.docx` descargable. |
| `/analizar-demanda-v2` | POST | `DemandaTestControllerV2` | Endpoint de prueba: recibe el PDF (`MultipartFile`) y un *prompt*, ejecuta el RAG orquestado y retorna el texto de la resolución. |

---

## 13. Glosario de términos técnicos

- **RAG (Retrieval-Augmented Generation):** técnica que enriquece la generación de un modelo de lenguaje con información recuperada dinámicamente de una base de conocimiento externa, en lugar de depender únicamente del conocimiento paramétrico del modelo.
- **Embedding:** representación numérica densa (vector de números reales) que captura el significado semántico de un texto. Textos semánticamente similares producen vectores cercanos en el espacio.
- **Vector Search / `findNeighbors`:** búsqueda de los vecinos más cercanos (*nearest neighbors*) de un vector de consulta dentro de un índice de vectores, según una métrica de similitud.
- **Index Endpoint:** infraestructura pública de Vertex AI que sirve consultas de búsqueda vectorial contra uno o varios índices desplegados.
- **Deployed Index:** índice de vectores efectivamente desplegado y servido dentro de un Index Endpoint.
- **`publicEndpointDomainName`:** dominio dedicado y público del Index Endpoint contra el cual deben dirigirse las consultas `findNeighbors`.
- **Data Store (Vertex AI Search):** almacén gestionado de documentos sobre el que Vertex realiza recuperación de forma automática mediante una *Retrieval Tool*. Reemplazado en esta implementación por el RAG orquestado.
- **GAPIC:** clases cliente generadas automáticamente (Google API Client) a partir de las definiciones de servicio; en este caso solo ofrecían transporte gRPC para las APIs de embeddings y match.
- **Transporte REST vs gRPC:** dos mecanismos de comunicación con las APIs de Google. Aquí se usa REST (HTTP/JSON) por compatibilidad con la *location* `global` y por uniformidad en el manejo del proxy.
- **`thinking budget`:** presupuesto de tokens que los modelos Gemini de razonamiento dedican a "pensar" antes de producir la salida. Desactivarlo (`0`) acelera tareas simples.
- **Modelo Flash vs Pro:** *Flash* es una variante optimizada para velocidad y costo; *Pro* es la variante de máxima capacidad de razonamiento y calidad.
- **`inlineData` vs `fileData`:** dos formas de adjuntar un archivo a una petición de Gemini. `inlineData` incrusta los bytes (en base64) en el cuerpo; `fileData` referencia el archivo por URI (`gs://...`), leyéndolo del lado del servidor.
- **Proxy de egress:** servidor intermediario obligatorio para el tráfico saliente de la red institucional del Poder Judicial hacia Internet; en este caso, el cuello de botella de throughput de subida.
- **`chunk` normativo:** fragmento de texto normativo (artículo o inciso) con sus metadatos, almacenado en Redis e identificado por el `datapointId` del índice vectorial.

---

*Fin del documento.*
