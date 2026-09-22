# PLAN — Asistente de Sistema para Agora (VoiceInteractionService + Screen Context + Voz)

**Fecha:** 2026-09-15
**Estado:** Fases 1–4 implementadas (build + tests OK; verificación manual en dispositivo pendiente)
**Inspiración:** Paridad con la activación de Gemini vía long-press del botón power, overlay con contexto de pantalla ("Ask about screen") y conversación por voz.

---

## 1. Objetivo

Que Agora pueda registrarse como **Digital Assistant App** del sistema Android, de modo que:

1. **Long-press del botón power** (o swipe desde esquina / long-press Home, según dispositivo) abra Agora como overlay sobre cualquier app.
2. El overlay pueda **adjuntar la pantalla actual como contexto** (screenshot vía `onHandleScreenshot()` + texto/jerarquía vía `AssistStructure`), equivalente al "Ask about screen" de Gemini.
3. El usuario pueda **dictar por voz** su prompt (speech-to-text) desde el overlay.
4. (Fase posterior, opcional) Conversación **voice-to-voice** estilo Gemini Live usando la Gemini Live API (WebSocket) con la API key del usuario (BYOK).

Todo reutilizando el pipeline de generación existente (`MessageGenerationController` → mailbox → reducer → `GenerationManager`), sin crear un pipeline paralelo (prohibido por `development/README.md` §4.6).

---

## 2. Investigación: cómo lo hace Gemini (referencia)

| Función Gemini | Mecanismo Android |
|---|---|
| Long-press power abre el asistente | El sistema lanza el `VoiceInteractionSession` de la app registrada como *Default digital assistant app* (Settings → Apps → Default apps). |
| "Ask about screen" | **Assist API**: `onHandleAssist(AssistStructure)` entrega la jerarquía de vistas como texto; `onHandleScreenshot()` entrega un `Bitmap` real de la pantalla. El usuario controla esto en Settings ("Use text from screen" / "Use screenshot"). |
| Overlay sobre otras apps | Ventana de sesión de voz con z-order especial del sistema (no es `TYPE_APPLICATION_OVERLAY` común). |
| "Hey Google" | Hotword always-on vía `VoiceInteractionService` (solo para el asistente por defecto). **Fuera de scope** para esta fase. |
| Gemini Live (voice-to-voice) | Propietario en la app; el equivalente abierto es la **Gemini Live API** (WebSocket bidireccional de audio), usable con BYOK. |

Referencias:
- https://developer.android.com/training/articles/assistant
- Sample oficial: `platform/development/samples/VoiceInteractionService` (android.googlesource.com)
- Ejemplo real en producción (open source): Home Assistant Companion App (`home-assistant/android`) — se registra como asistente y responde al long-press de power.

**Restricciones conocidas:**
- Solo **una** app puede ser la asistente por defecto; el usuario debe cambiarla manualmente en Settings del sistema (Agora solo puede enviarlo ahí con un intent).
- El screenshot de otras apps **solo** está disponible para la app asistente activa; sin ese rol no hay forma de capturar pantalla ajena (MediaProjection exige consentimiento por sesión y no aplica aquí).
- `BIND_VOICE_INTERACTION` es permiso de firma: lo otorga el sistema solo a la app elegida como asistente.

---

## 3. Estado actual de Agora (hallazgos de código)

| Necesidad | Estado actual |
|---|---|
| `RECORD_AUDIO` | **No declarado** en `AndroidManifest.xml`. No existe `SpeechRecognizer`/`MediaRecorder`/`AudioRecord` en el código. |
| `VoiceInteractionService` | No existe. |
| Overlay | No hay `TYPE_APPLICATION_OVERLAY` ni `SYSTEM_ALERT_WINDOW`. La ventana de sesión de voz **no los requiere** (z-order propio del framework). |
| Abrir conversación desde fuera | `MainActivity` ya maneja `agora://conversation/{id}` y `EXTRA_CONVERSATION_ID`; `ConversationSelectionController.createNewChat()` / `selectConversation(id)` existen. |
| Adjuntar imágenes a mensajes | Pipeline completo: `SelectedAttachment` → `AttachmentMeta` → providers multimodales (`GeminiProvider` envía `ApiInlineData` base64). Reutilizable para el screenshot. |
| Generación headless | `TaskExecutionEngine` + `AutomationExecutionGate` ya ejecutan generación sin UI (automation). Patrón reutilizable para el overlay. |
| Gemini Live API / WebSocket | **No existe**; `GeminiProvider` usa SSE (`streamGenerateContent?alt=sse`). Voice-to-voice requeriría cliente WebSocket nuevo. |
| Settings | Patrón establecido: `SettingsCategory` en `baseSettingsGroups` + `*Page.kt` con `CollapsingSettingsScaffold` (contrato `development/settings-ui-ux.md`). |

---

## 4. Diseño propuesto

### 4.1 Componentes nuevos

```
app/src/main/java/com/newoether/agora/
├── assistant/
│   ├── AgoraVoiceInteractionService.kt      # entry point del sistema; onReady()
│   ├── AgoraVoiceInteractionSessionService.kt
│   ├── AgoraVoiceInteractionSession.kt      # overlay: UI Compose, captura contexto
│   └── AssistContextCapture.kt              # AssistStructure → texto; Bitmap → archivo
```

- **`AgoraVoiceInteractionService`** — declarado con `android:permission="android.permission.BIND_VOICE_INTERACTION"` + meta-data `android.voice_interaction` (XML con `supportsAssist` + `supportsLaunchVoiceAssistFromKeyguard` según decisión).
- **`AgoraVoiceInteractionSession`** — ventana overlay (Compose). En `onHandleAssist()` / `onHandleScreenshot()`:
  1. Guarda screenshot como archivo en storage privado (mismo mecanismo que attachments de cámara).
  2. Extrae texto de `AssistStructure` (truncado, p.ej. 8 KB) como contexto textual.
  3. Crea conversación nueva (o reutiliza "conversación de asistente" dedicada — **decisión abierta §6**).
  4. Adjunta screenshot como `SelectedAttachment` y el texto como parte del prompt del usuario.
- **Voz**: botón de micrófono en el overlay usando `android.speech.SpeechRecognizer` (`createOnDeviceSpeechRecognizer()` disponible desde Android 12 / API 31, no Android 13; Agora tiene minSdk 24, así que requiere guard con `isOnDeviceRecognitionAvailable()` y fallback a `createSpeechRecognizer()` en versiones/dispositivos sin motor local — invariante §3.2.9 de `AGENTS.md`). Sin permisos extra más allá de `RECORD_AUDIO`. El texto reconocido se inserta en el campo de entrada. **No** se graba/persiste audio.
- Al enviar: se invoca `MessageGenerationController` con el `conversationId` — pipeline ordinario, un Run nuevo, sin excepciones.

### 4.2 Manifiesto

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<service
    android:name=".assistant.AgoraVoiceInteractionService"
    android:permission="android.permission.BIND_VOICE_INTERACTION"
    android:exported="true">
    <meta-data android:name="android.voice_interaction"
        android:resource="@xml/assistant_interaction_service"/>
    <intent-filter>
        <action android:name="android.service.voice.VoiceInteractionService"/>
    </intent-filter>
</service>
```

**NO** se agrega `SYSTEM_ALERT_WINDOW`: la ventana de sesión de voz usa el z-order del framework.

### 4.3 Settings

Nueva categoría **"Asistente del sistema"** (grupo Tools o uno propio — decisión §6):

- Estado: si Agora es el asistente por defecto (lectura vía `Settings.Secure` / `RoleManager` no aplica; se detecta comparando el componente activo de `voice_interaction_service`).
- Botón "Abrir ajustes del sistema" → `Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)` para que el usuario lo active.
- Toggle: adjuntar screenshot automáticamente al activar (default on).
- Toggle: incluir texto de pantalla automáticamente (default on).
- Toggle: entrada de voz en el overlay (default on; solicita `RECORD_AUDIO` en runtime).
- Strings en `res/values*/` para las **11 locales** (patrón del plan de tools anterior).

### 4.4 Contrato de módulo

Nuevo `development/system-assistant.md` siguiendo el formato de `development/assistant-device-tools.md`:

- Definición de categoría, invariantes (opt-in, no pipeline paralelo, screenshot solo vía Assist API, fail-closed si no es asistente por defecto).
- Registro en `AGENTS.md` §5.
- Portabilidad: los toggles se exportan en `PortableSettingsArchive` (patrón ya establecido con `assistant*Enabled`).

### 4.5 Voice-to-voice (Fase 2, opcional, separada)

- Cliente WebSocket para **Gemini Live API** (`gemini-2.5-flash-native-audio` o modelo vigente), audio PCM 16 kHz in / 24 kHz out.
- Requiere: captura `AudioRecord`, reproducción `AudioTrack`, VAD, interrupciones.
- **Solo si** se aprueba explícitamente; es un subsistema considerable y merece su propio plan. No bloquea las fases 1–3.

---

## 5. Fases de implementación

### Fase 1 — Registro como asistente + overlay mínimo
1. `RECORD_AUDIO` en manifest + `xml/assistant_interaction_service.xml`.
2. `AgoraVoiceInteractionService` / `SessionService` / `Session` con overlay Compose mínimo (campo de texto + botón enviar + botón mic).
3. Al enviar: crear conversación nueva y rutar por el pipeline ordinario; mostrar respuesta en el overlay (solo lectura del `ChatViewModel` de esa conversación).
4. Detección de "soy el asistente por defecto" + entrada en Settings con deep link a ajustes del sistema.
5. Contrato `development/system-assistant.md` + registro en `AGENTS.md` §5.

**Criterio de aceptación:** con Agora como asistente por defecto, long-press power abre el overlay, se escribe un prompt y se genera respuesta en una conversación visible luego en la lista de chats.

### Fase 2 — Contexto de pantalla
1. `AssistContextCapture`: `onHandleAssist` → texto de `AssistStructure`; `onHandleScreenshot` → `Bitmap` → archivo en attachments.
2. Chips en el overlay: "Preguntar sobre esta pantalla" (auto-adjunta screenshot + texto), paridad con el UX de Gemini.
3. Respetar flags de la app origen (`FLAG_SECURE` → el sistema ya no entrega screenshot; mostrar estado "no disponible").
4. Toggles en Settings + strings en 11 locales + portabilidad en `.agora`.

**Criterio de aceptación:** sobre Chrome/YouTube, el overlay ofrece "Ask about screen", adjunta la captura y el modelo responde sobre el contenido.

### Fase 3 — Entrada de voz
1. `SpeechRecognizer` en el overlay (permiso runtime `RECORD_AUDIO`).
2. Resultados parciales en el campo de texto; envío manual (no auto-submit).
3. Sin persistencia de audio.

**Criterio de aceptación:** dictar un prompt en español/inglés inserta el texto y permite enviarlo.

### Fase 4 — Voice-to-voice con Gemini Live API
Cliente WebSocket + audio full-duplex. Ver diseño completo en **§10**.

---

## 6. Decisiones del owner (2026-09-15, confirmadas)

1. **Conversación destino del overlay:** **configurable en Settings** — toggle "Reutilizar conversación del asistente" (default off = conversación nueva por activación, paridad Gemini; on = conversación persistente dedicada).
2. **Ubicación en Settings:** **grupo propio** "Asistente" (fuera de Tools), con su categoría "Asistente del sistema".
3. **Flavors:** habilitado en **ambos** (fdroid + play); no depende de sandbox ni SMS.
4. **Hotword ("Hey Agora"):** **excluido** (requiere `AlwaysOnHotwordDetector`, DSP y modelo de wake word).
5. **Fase 4 (voice-to-voice):** **plan separado**, posterior a Fases 1–3.

**Corrección de revisión (2026-09-15):** `createOnDeviceSpeechRecognizer()` existe desde **API 31 (Android 12)**, no API 33. Como minSdk es 24, la Fase 3 debe guardar con `SpeechRecognizer.isOnDeviceRecognitionAvailable()` (API 31+) y caer a `createSpeechRecognizer()` (red) en el resto — invariante §3.2.9 de AGENTS.md.

---

## 7. Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| El usuario no cambia el asistente por defecto → feature "muerta" | Detección + deep link a ajustes + copy claro; feature invisible si no está activa (fail-closed). |
| Apps con `FLAG_SECURE` (banca, DRM) no entregan screenshot | El sistema ya lo bloquea; mostrar estado "no disponible" sin crashear. |
| `AssistStructure` puede contener datos sensibles (passwords ocultos vienen enmascarados, pero texto libre no) | Toggles individuales; el texto solo se envía al provider configurado por el usuario (BYOK, mismo threat model que el resto de Agora). |
| Overlay Compose dentro de `VoiceInteractionSession` | Patrón probado (Home Assistant lo hace); usar `LifecycleOwner`/`SavedStateRegistryOwner` manual en la ventana de sesión. |
| Fragmentación OEM (Samsung restringe la coexistencia de `VoiceInteractionService` + `ACTION_ASSIST`) | **Resuelto en código (2026-09-17).** Diagnóstico de campo en Samsung One UI: con `AgoraVoiceInteractionService`/`SessionService` y `AssistantActivity` (`ACTION_ASSIST`) declarados a la vez, el renglón "Asistente digital" del botón lateral no resolvía el nombre ("Ninguno") y en un segundo intento el long-press dejó de invocar nada. Confirmado experimentalmente que deshabilitar el `VoiceInteractionService` (dejando solo `AssistantActivity`) arregla ambos síntomas — coincide con Kai (app hermana del mismo owner, solo `ACTION_ASSIST`, nunca tuvo el problema) y con la documentación de `RoleManager.ROLE_ASSISTANT` ("at least one of" VoiceInteractionService o ACTION_ASSIST — correr ambos a la vez es elección de Agora para la paridad Gemini, no un requisito de la plataforma). Fix: `AssistantOemCompat.applyOnFirstRun()` (invocado desde `AgoraApplication.onCreate()`) deshabilita `AgoraVoiceInteractionService`/`SessionService` en runtime **solo cuando `Build.MANUFACTURER == "samsung"`**, vía `PackageManager.setComponentEnabledSetting`; el resto de fabricantes conserva ambos mecanismos y la experiencia completa tipo Gemini. Alcance intencionalmente acotado a Samsung con evidencia real; si aparece el mismo conflicto en otro fabricante, se agrega ahí con su propia evidencia fechada, no se generaliza a priori. |
| Tamaño de archivo: `SettingsScreen.kt` y límites de 999 líneas | Página nueva en archivo propio (`SettingsAssistantPage.kt`); strings en archivo propio si es necesario. |

---

## 8. Verificación

- `gradlew.bat testFdroidDebugUnitTest testPlayDebugUnitTest verifyKotlinFileSize`
- Tests unitarios: parsing de `AssistStructure` (mock), truncado de texto, gating por toggles, comportamiento cuando no es asistente por defecto.
- Verificación manual en dispositivo: activación por power button, screenshot sobre app normal, `FLAG_SECURE`, voz, respuesta visible en lista de conversaciones.

---

## 9. Referencias

- https://developer.android.com/training/articles/assistant
- https://developer.android.com/reference/android/service/voice/VoiceInteractionService
- https://developer.android.com/reference/android/service/voice/VoiceInteractionSession
- Sample AOSP: `platform/development/samples/VoiceInteractionService`
- Home Assistant Companion (producción, open source): https://github.com/home-assistant/android
- Gemini Live API: https://ai.google.dev/gemini-api/docs/live (Fase 4)
- Gemini Live API — WebSockets API reference: https://ai.google.dev/api/live
- Gemini Live API — Get started with WebSockets: https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket
- Gemini Live API — Capabilities guide (audio formats, VAD, transcripción, voces): https://ai.google.dev/gemini-api/docs/live-api/capabilities
- Gemini Live API — Session management (resumption, GoAway, context window compression): https://ai.google.dev/gemini-api/docs/live-api/session-management
- Gemini Live API — Ephemeral tokens: https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens
- Gemini Live API — Tool use / function calling: https://ai.google.dev/gemini-api/docs/live-tools
- Android — Foreground service types requeridos en Android 14 (`microphone`): https://developer.android.com/about/versions/14/changes/fgs-types-required
- Firebase AI Logic — Gemini Live API en Android (SDK descartado para Agora, ver §10.2): https://firebase.google.com/docs/ai-logic/live-api
- Contratos internos: `development/README.md`, `development/settings-ui-ux.md`, `development/message-generation.md`, `development/assistant-device-tools.md`

---

## 10. Fase 4 — Voice-to-voice con Gemini Live API (plan detallado)

**Estado:** implementada (2026-09-16). Investigado con `mcp__google-developer-knowledge` (developer.android.com, firebase.google.com) y `mcp__google-api-docs` (ai.google.dev / SDKs oficiales) el 2026-09-16; protocolo reverificado contra la referencia vigente al implementar.

### 10.1 Qué es la Live API (resumen verificado)

La Live API de Gemini es un **WebSocket con estado** (`BidiGenerateContent`), distinto del `streamGenerateContent` (SSE) que ya usa `GeminiProvider`:

- **Endpoint:** `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<API_KEY>`.
- **Primer mensaje obligatorio:** `{"setup": BidiGenerateContentSetup}` con `model`, `responseModalities: ["AUDIO"]`, `systemInstruction`, `tools`, `speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName`, `inputAudioTranscription: {}`, `outputAudioTranscription: {}`, `sessionResumption: {}`, `contextWindowCompression: {slidingWindow: {}}`. La config **no se puede cambiar** una vez abierta la conexión.
- **Mensajes del cliente** (exactamente uno de estos campos por mensaje): `setup`, `clientContent`, `realtimeInput` (`audio`, `video`, `text`, `audioStreamEnd`, `activityStart`/`activityEnd` si VAD manual), `toolResponse`.
- **Audio de entrada:** PCM16 little-endian, 16 kHz, mono, `mimeType: "audio/pcm;rate=16000"`, base64 en `realtimeInput.audio.data`.
- **Audio de salida:** siempre PCM16 little-endian, **24 kHz**, en `serverContent.modelTurn.parts[].inlineData.data` (base64).
- **VAD automático (default, servidor):** detecta inicio/fin de turno; al detectar interrupción del usuario envía `serverContent.interrupted: true` — el cliente **debe** descartar inmediatamente el audio en cola de reproducción (barge-in). No se recomienda desactivarlo (VAD manual exige buffers propios y umbrales ≥500 ms para no cortar palabras).
- **Transcripciones:** con `inputAudioTranscription`/`outputAudioTranscription` en el setup, el servidor entrega `serverContent.inputTranscription.text` / `outputTranscription.text` — es la vía para persistir la conversación como texto (ver §10.4).
- **Límites de sesión:** audio-only = 15 min; conexión física ≈10 min (se recicla sola). `sessionResumption` (handle válido 2 h) permite reconectar sin perder la sesión lógica; `contextWindowCompression` con `slidingWindow` evita el corte de 15 min. El servidor avisa con `GoAway.timeLeft` antes de cerrar.
- **Function calling:** el servidor manda `toolCall.functionCalls[]`; el cliente ejecuta y responde con `{"toolResponse": {"functionResponses": [...]}}`. Los modelos recientes (`gemini-3.8-live`) soportan `NON_BLOCKING` (async) por defecto.
- **Modelos (a confirmar en el momento de implementar; son preview y cambian seguido):** `gemini-3.8-live` (recomendado, uso general), `gemini-3.8-live-extended-thinking` (razonamiento en segundo plano), `gemini-2.5-flash-native-audio-preview-09-2025`/`-12-2025` (aún soportados). Ventana de contexto: 128k tokens en modelos de audio nativo.

### 10.2 Decisión: WebSocket propio (OkHttp) vs. Firebase AI Logic SDK

Google ofrece dos caminos para Android: (a) WebSocket crudo contra `generativelanguage.googleapis.com` con la API key del usuario, o (b) el SDK **Firebase AI Logic** (`Firebase.ai(...).liveModel(...)`, `LiveSession.startAudioConversation()`), que maneja captura/reproducción de audio por ti.

**Se descarta (b) para Agora:**
1. Firebase AI Logic requiere un **proyecto Firebase** (`google-services.json`) — incompatible con el modelo BYOK de Agora, donde el usuario pega *cualquier* API key propia sin passthrough por infraestructura de Google/Anthropic ajena a su cuenta.
2. Introduce dependencias de **Google Play Services / Firebase** en el flavor `fdroid`, que hoy está limpio de eso (§14 de `AGENTS.md`: `fdroid` no lleva servicios propietarios).
3. No aporta nada que el WebSocket crudo no dé ya: `HttpClient`/OkHttp ya está en el proyecto y soporta `WebSocket` nativamente (misma librería que usa `GeminiProvider` para SSE).

**Decisión:** cliente WebSocket propio sobre OkHttp (`okhttp3.WebSocket`), igual en ambos flavors.

### 10.3 Autenticación: reutilizar la API key existente, sin ephemeral tokens

Google recomienda **ephemeral tokens** (`POST /v1beta/auth_tokens`, válidos ~30 min) para conexiones *cliente-a-servidor* directas, porque normalmente hay un **backend propio** que posee la API key real y minta tokens de un solo uso para no exponerla al cliente.

**Agora no tiene ese backend ni ese límite de confianza que proteger:** cada usuario ya guarda su propia Gemini API key en el dispositivo (`SecretCrypto`, Android Keystore AES-256-GCM, con fallback a texto plano documentado en `AGENTS.md` §13) — es exactamente el mismo modelo de amenaza que usa `GeminiProvider` hoy para las llamadas SSE con `x-goog-api-key`. Pedir un ephemeral token significaría usar la propia API key del usuario para autenticar la petición que la protege — una vuelta extra sin beneficio real, porque no hay un tercero (otro usuario final) del que proteger la key.

**Decisión:** el cliente Live usa la API key BYOK del proveedor `google` tal cual, igual que `GeminiProvider`:

```kotlin
val activeKey = settings.apiKeys.value
    .find { it.id == settings.activeApiKeyIds.value[Constants.PROVIDER_GOOGLE] }
    ?.key.orEmpty()
```

como query param `?key=` (o `Authorization: Token`, ambos válidos según la referencia). No se agrega UI de credenciales nueva.

### 10.4 Persistencia: por qué NO pasa por `GenerationManager` (excepción justificada, §4.5 AGENTS.md)

La Live API es un socket bidireccional con muchos turnos lógicos por conexión y turn-taking dirigido por el servidor (VAD, interrupciones a mitad de turno). El pipeline ordinario (`ConversationCommandMailbox` → `ConversationRuntimeReducer` → `GenerationManager` → `ProviderPassRunner`) modela **un Run = una petición/respuesta de un `LlmProvider.generateResponse()` (SSE)**; no hay forma limpia de mapear un socket de larga duración con N turnos e interrupciones asíncronas a ese modelo sin inventar estados falsos.

Por eso la Fase 4 es una **excepción explícita y acotada**, documentada aquí y en el contrato de módulo antes de implementar (regla de `AGENTS.md` §4.5: toda abstracción nueva debe nombrar qué condición cumple, por qué el dueño existente no alcanza, y qué no duplica):

- **Condición que cumple:** frontera real de transporte/temporalidad — audio full-duplex de baja latencia con turnos iniciados por el servidor, no expresable como un `ProviderPassRunner` de una sola pasada.
- **Por qué `GenerationManager` no alcanza:** asume un único flujo SSE de texto por Run; la Live API es bidireccional y con estado sobre un socket físico que sobrevive a muchos turnos lógicos.
- **Qué NO duplica:** la persistencia sigue pasando por `ConversationRepository` (Room = verdad durable, invariante respetado). No se crea un segundo grafo de conversación ni una segunda cola — solo un segundo **transporte de entrada** hacia los mismos `ChatMessage`/conversación de siempre. La lista de chats, el árbol de mensajes y el branching ven mensajes ordinarios después del hecho.

**Mecánica concreta:** al completar cada turno (`serverContent.turnComplete: true`), `LiveVoiceSessionController` escribe directamente vía `ConversationRepository`:
1. Un `ChatMessage` de `Participant.USER` con `inputTranscription.text` acumulado del turno.
2. Un `ChatMessage` de `Participant.MODEL` con `outputTranscription.text` acumulado, `modelName = <modelo live usado>`.

en la conversación con `origin = "assistant-voice"` (decisión confirmada, ver §10.9) — origen separado del overlay de texto (`"assistant"`), con su propio toggle de reutilización. El audio en sí **no se persiste** — coherente con la Fase 3 ("no se graba/persiste audio").

### 10.5 Componentes nuevos

```
app/src/main/java/com/newoether/agora/assistant/live/
├── GeminiLiveClient.kt          # OkHttp WebSocket: connect/setup/send/receive, reconexión con sessionResumption
├── LiveVoiceModels.kt           # @Serializable DTOs: BidiGenerateContentSetup/ClientContent/RealtimeInput/ToolResponse
│                                 # y ServerMessage (serverContent, toolCall, goAway, sessionResumptionUpdate, usageMetadata)
├── LiveAudioCapture.kt          # AudioRecord 16kHz mono PCM16 + AcousticEchoCanceler/NoiseSuppressor si disponibles
├── LiveAudioPlayback.kt         # AudioTrack streaming 24kHz mono PCM16; flush inmediato en `interrupted`
├── LiveVoiceSessionController.kt # orquesta captura→socket→reproducción; persiste turnos (ConversationRepository)
└── LiveVoiceForegroundService.kt # foregroundServiceType="microphone"; mantiene la llamada si la UI pasa a background

app/src/main/java/com/newoether/agora/ui/assistant/
└── VoiceModeScreen.kt           # Compose full-screen: estado de la llamada, mute, colgar, indicador de nivel de voz
```

**Por qué NO vive en `AgoraVoiceInteractionSession` (el overlay de Fases 1–3):** la ventana de sesión de voz es efímera por diseño (se cierra si el usuario navega fuera) y una llamada de voz de varios minutos con captura de micrófono en background necesita un foreground service, algo que una `VoiceInteractionSession` no sostiene bien. Entrada propuesta: botón dedicado en el overlay ("Llamar") que hace `finish()` de la sesión y abre `VoiceModeScreen` dentro de la app — mismo patrón que `openInApp()` ya usa para "Open in Agora".

### 10.6 Manifiesto (adiciones)

```xml
<!-- Voice-to-voice: captura de micrófono en foreground service (obligatorio en Android 14+
     para foregroundServiceType="microphone"; RECORD_AUDIO ya declarado en Fase 3). -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

<service
    android:name=".assistant.live.LiveVoiceForegroundService"
    android:foregroundServiceType="microphone"
    android:exported="false" />
```

**Restricción verificada:** `RECORD_AUDIO` es un permiso *while-in-use*; un foreground service tipo `microphone` **no puede arrancar con la app en background** (salvo excepciones que no aplican aquí). Por eso `LiveVoiceForegroundService` debe arrancar siempre desde `VoiceModeScreen` en foreground, nunca desde un trigger en background/automation.

### 10.7 Pipeline de audio

- **Captura:** `AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)`; adjuntar `AcousticEchoCanceler.create()` / `NoiseSuppressor.create()` cuando `Effect.isAvailable(...)` (no todos los dispositivos los traen). `AudioManager.mode = MODE_IN_COMMUNICATION` mientras la llamada esté activa; restaurar el modo previo al colgar.
- **Envío:** trocear en frames (~20–40 ms), base64, `realtimeInput.audio` con `mimeType="audio/pcm;rate=16000"`. Al silenciar el mic, enviar `realtimeInput.audioStreamEnd: true` (VAD automático activo).
- **Recepción/reproducción:** `AudioTrack` en modo streaming, 24000 Hz mono PCM16; encolar los `inlineData.data` de `serverContent.modelTurn.parts[]` a medida que llegan. En `serverContent.interrupted: true`, **descartar inmediatamente** cualquier frame en cola/reproducción (barge-in real, no solo dejar de encolar).
- **Reconexión:** guardar el último `sessionResumptionUpdate.newHandle`; al recibir `goAway` o al caerse el socket, reconectar automáticamente con `sessionResumption.handle = <último handle>` dentro de la ventana de 2 h (decisión confirmada, §10.9 punto 4). Mientras se reconecta, `VoiceModeScreen` muestra un indicador breve "Reconectando…" — la reconexión es transparente para el audio/estado de la sesión, pero no se oculta al usuario que hubo un corte.

### 10.8 Settings

Extiende `AssistantToolSettings` (mismo archivo, mismo store DataStore) o, si el tamaño lo exige por la política de 999 líneas, un `LiveVoiceSettings.kt` hermano:

- `voiceToVoiceEnabled: Boolean` (default **off** — feature de preview, consumo de tokens continuo).
- `voiceToVoiceReuseConversationEnabled: Boolean` (default **on** — a diferencia del overlay de texto, una llamada de voz es naturalmente continua; reutiliza la conversación `"assistant-voice"` más reciente salvo que el usuario apague el toggle).
- `voiceToVoiceModelId: String` (default precargado **`gemini-3.1-flash-live-preview`** — reverificado contra la documentación vigente el 2026-09-16: es el modelo Live recomendado por Google (reemplazo oficial de `gemini-2.0-flash-live-001`, `gemini-live-2.5-flash-preview` y `gemini-2.5-flash-native-audio-preview-12-2025`), sin shutdown anunciado, con **free tier confirmado** (free of charge en pricing.md) y ventana de contexto de 131,072 tokens. El default original del plan (`gemini-2.5-flash-native-audio-preview-09-2025`) fue retirado de la documentación y `gemini-3.8-live` ya no figura — se descartan ambos. El campo sigue siendo texto libre editable porque los modelos Live son preview y cambian seguido. No tiene sentido reusar `fetchModelsForProvider`, que filtra por soporte de `generateContent` y no ve modelos `BidiGenerateContent`).
- `voiceToVoiceVoiceName: String` (nombre de voz TTS, p.ej. "Kore"/"Puck"/"Fenrir"; lista fija de voces conocidas con opción de texto libre).
- Página propia `SettingsLiveVoicePage.kt` dentro del grupo **Asistente** (mismo grupo que §4.3), con aviso explícito: "El audio se envía directamente a Gemini con tu API key; no se guarda en el dispositivo" y una nota de costo (los modelos de audio nativo consumen tokens de forma continua, no solo por mensaje).
- Toggles exportados/importados por `PortableSettingsArchive` y limpiados en el reset completo, mismo patrón que el resto de `AssistantToolSettings`.

### 10.9 Decisiones del owner (2026-09-16, confirmadas)

1. **Origen de conversación:** separado — `origin = "assistant-voice"`, distinto del overlay de texto (`"assistant"`), con su propio toggle `voiceToVoiceReuseConversationEnabled` (default **on**, a diferencia del overlay de texto). Justificación: las transcripciones de voz son más ruidosas/imprecisas que un prompt escrito, y una llamada de voz es naturalmente continua (se espera retomarla), a diferencia del uso puntual del overlay de texto. Mezclarlas en la misma conversación ensuciaría el historial y acoplaría dos comportamientos de reutilización que el usuario querría configurar por separado.
2. **Modelo por defecto:** fijo y precargado — **`gemini-3.1-flash-live-preview`** (reverificado contra la documentación vigente el 2026-09-16; el default original propuesto, `gemini-2.5-flash-native-audio-preview-09-2025`, fue retirado de la docs y se descartó). Elegido por ser el modelo Live recomendado por Google para uso general, sin shutdown anunciado y con **free tier confirmado** (free of charge en pricing.md). Dejarlo vacío rompe el first-run (el usuario activa el toggle, presiona "Llamar" y no pasa nada). El campo sigue siendo texto libre editable en Settings porque los modelos Live son preview y cambian con frecuencia — este string debe reverificarse contra la documentación vigente en cada actualización mayor de Agora, no darse por definitivo hoy.
3. **Herramientas (function calling) en v1:** confirmado que Fase 4 v1 es **solo conversación, sin tools**. Function calling en modo `NON_BLOCKING` sobre un transporte ya de por sí excepcional (fuera del pipeline ordinario) añade una capa de orquestación asíncrona completa que no aporta al valor mínimo de "llamada de voz funcional". Se deja explícitamente para una **Fase 4.1** posterior, reutilizando los `ToolProvider` existentes.
4. **Límite de 15 min:** solución intermedia confirmada — `contextWindowCompression` (`slidingWindow: {}`) se incluye **desde v1** en el `setup` (es una línea de configuración, no una feature grande), y la reconexión vía `sessionResumption` se implementa como **automática/transparente desde v1** porque ese mismo mecanismo hay que construirlo de todas formas para sobrevivir cortes de red normales en un cliente móvil — no tendría sentido diferirlo a la 4.1 solo para reimplementarlo después. Única concesión a la transparencia con el usuario: `VoiceModeScreen` muestra un indicador breve "Reconectando…" durante el hueco (ver §10.7), en vez de tragarse el corte en silencio.

### 10.10 Riesgos específicos de Fase 4

| Riesgo | Mitigación |
|---|---|
| API en preview: nombres de modelo y campos cambian sin aviso | Modelo configurable como texto libre en Settings, no hardcodeado en código de producción salvo como sugerencia. |
| Costo: streaming de audio consume tokens continuamente mientras la llamada está abierta | Indicador visible de "En llamada" + botón de colgar siempre accesible; aviso de costo en Settings antes de activar el toggle. |
| Eco/calidad en dispositivos sin cancelador de eco por hardware | Chequear `AcousticEchoCanceler.isAvailable()`; si no hay, sugerir auriculares en el copy de `VoiceModeScreen`. |
| Foreground service de micrófono no puede arrancar en background (Android 14+) | `LiveVoiceForegroundService` solo se arranca desde `VoiceModeScreen` en primer plano, nunca desde automation/background triggers. |
| Confusión con la Fase 3 (dictado por texto) | Entradas de UI claramente distintas: mic del overlay = dictado (texto), botón "Llamar" = voice-to-voice (`VoiceModeScreen`). |
| Tamaño de archivo (999 líneas) | Página de Settings y DTOs en archivos propios desde el inicio, sin esperar a que `AssistantToolSettings`/`SettingsScreen.kt` crezcan. |

### 10.11 Verificación

- Unit: (de)serialización de los DTOs `setup`/`realtimeInput`/`serverContent`/`toolResponse`; troceo y base64 de PCM; mapeo turno completado → `ChatMessage` (participante/origen correctos); lógica pura de persistencia del handle de resumption (testable sin socket real).
- Manual: llamada real en dispositivo, interrupción (barge-in) hablando encima del modelo, ciclo foreground/background del service, reconexión tras `goAway`/pérdida de red, build `fdroid` sin nuevas dependencias de Play Services/Firebase, build `play` sin regresiones.
- Gate estándar: `gradlew.bat -p build-logic test :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest verifyKotlinFileSize --no-daemon --max-workers=1 --stacktrace`.

### 10.12 Hallazgos reales de verificación manual (2026-09-17)

La verificación manual en dispositivo (marcada pendiente en §10.11) se realizó y encontró varios bugs reales, todos corregidos:

1. **Frames del servidor llegaban como WebSocket binario, no texto.** `GeminiLiveClient` solo implementaba `onMessage(webSocket, text: String)`; la Live API manda `setupComplete`/`serverContent`/etc. como frames **binarios** (opcode `0x2`) que contienen JSON UTF-8. Sin el overload `onMessage(webSocket, bytes: ByteString)`, cada respuesta del servidor se descartaba en silencio — indistinguible de "el servidor nunca responde". Confirmado byte a byte contra el wire real con `websocat` antes de corregir. Fix: se agregó el overload binario, decodificando `bytes.utf8()` hacia el mismo `dispatch()`.
2. **`settings.selectedModel` es un placeholder legacy sin mantener.** El overlay de texto (`AssistantOverlayState`) creaba conversaciones sin `modelId` explícito, y `TaskExecutionEngine` caía de vuelta a `settings.selectedModel.value` sin validar — ese campo queda fijo en `Constants.EXAMPLE_MODEL_ID` (`"gemini-1.5-flash"`) porque la UI moderna gestiona el modelo vía `ConversationWorkspaceStore`, no ese `StateFlow`. Resultó en `Unable to resolve host "http://0.0.0.0"`. Fix: el overlay ahora resuelve el modelo contra `enabledModels` al crear la conversación, igual que el chat normal.
3. **`SpeechRecognizer` on-device sin fallback.** `isOnDeviceRecognitionAvailable()` puede devolver `true` sin el paquete de idioma instalado, cortando el reconocimiento al instante. Fix: fallback automático a `createSpeechRecognizer()` (red) si el motor on-device no entrega ningún resultado.
4. **Sin indicador visual de actividad de voz.** `VoiceModeScreen` no tenía ninguna animación. Fix: nivel RMS del PCM capturado expuesto como `onAudioLevel`, animando un círculo simple durante la llamada.
5. **VAD por defecto de Google es conservador.** Sin `realtimeInputConfig.automaticActivityDetection` explícito, el corte de turno tras silencio se sentía lento. Fix: `endOfSpeechSensitivity=END_SENSITIVITY_HIGH`, `startOfSpeechSensitivity=START_SENSITIVITY_HIGH`, `silenceDurationMs=500`, `prefixPaddingMs=20` (ver `LiveAutomaticActivityDetection`) — si se siente demasiado agresivo cortando a media frase, subir `silenceDurationMs` primero. **Corregido 2026-09-19:** después de hacerlo configurable (§ abajo), pruebas en dispositivo mostraron que `START_SENSITIVITY_HIGH` hace que el VAD del servidor **nunca detecte que el usuario empezó a hablar** (4 intentos, cero `serverContent`, con `gemini-2.5-flash-native-audio-preview-09-2025`) — lo opuesto a lo documentado por Google. `START_SENSITIVITY_LOW` funcionó de inmediato y consistentemente. Los tres presets de `VoiceSensitivity` ahora fijan `startOfSpeechSensitivity=LOW` siempre; solo `endOfSpeechSensitivity`/`silenceDurationMs`/`prefixPaddingMs` varían entre Paciente/Equilibrado/Rápido, lo cual de todas formas coincide con la queja original (cortar a media frase es un problema de fin de turno, no de inicio). Posible quirk de la API preview — revisar si un futuro modelo documenta otro comportamiento.
6. **Conflicto de manifest Samsung.** Ver la fila correspondiente en §7 (`AssistantOemCompat`).
7. **Carrera start→stop en `LiveVoiceForegroundService` (`ForegroundServiceDidNotStartInTimeException`/`SERVICE_FOREGROUND_CRASH_MSG`, 2026-09-18).** `stop(context)` llamaba `Context.stopService()` externo, que podía llegar a AMS antes de que el `onStartCommand` en curso ejecutara `startForeground()` — el sistema mata la app intencionalmente si un servicio arrancado con `startForegroundService()` se destruye sin haber satisfecho `fgRequired`. Se dispara sobre todo cuando `LiveVoiceSessionController.startCall()` publica `ENDED` casi de inmediato desde su corrutina en `Dispatchers.Default` (hilo de background), y `VoiceModeActivity` llama `stop()` directo desde ese callback. Causa raíz agravante confirmada: **`startCall()` leía `settings.apiKeys.value`/`activeApiKeyIds.value` sin cruzar `SettingsRepository.awaitInitialLoad()`** — el único consumidor en background de todo el proyecto que no lo hacía — produciendo un falso "sin API key" en cold start (proceso arrancado directo a `VoiceModeActivity` desde el botón 📞 del overlay) aunque el usuario sí tuviera una configurada. Fix de dos partes: (a) `LiveVoiceForegroundService.onStartCommand` ahora siempre llama `startForeground()` antes de procesar cualquier acción, y `stop()` se enruta como un comando (`ACTION_STOP` vía `startService()`) que reentra al mismo `onStartCommand` en vez de llamar `stopService()` externo — Android serializa las invocaciones de `onStartCommand` de un mismo componente, así que el start siempre satisface `fgRequired` antes de que cualquier stop encolado se procese; (b) `startCall()` ahora llama `settings.awaitInitialLoad()` antes de leer cualquier `.value`, eliminando el falso negativo de razón en vez de solo tolerar la carrera. La conversación original que reportó esto ya identificó también el helper existente `SettingsRepository.awaitActiveKey(provider)`, construido explícitamente para esta misma clase de bug (documentado en el propio repo con un comentario casi idéntico al síntoma observado aquí).

Con estos siete fixes, el pipeline de voice-to-voice quedó verificado de punta a punta en dispositivo real (captura → socket → transcripción → audio de respuesta → `turnComplete`).

8. **Eco acústico: el mic captaba la propia voz de la IA como si fuera el usuario (2026-09-19, más visible en preset "Rápido").** Causa raíz confirmada: `LiveAudioPlayback` reproducía con `AudioAttributes.USAGE_MEDIA`. El lado de captura (`LiveAudioCapture`) ya estaba bien configurado (`MediaRecorder.AudioSource.VOICE_COMMUNICATION`, `AudioManager.MODE_IN_COMMUNICATION`, `AcousticEchoCanceler` en la sesión correcta), pero el HAL de audio de Android solo expone como referencia de eco al `AcousticEchoCanceler` los streams de reproducción etiquetados como voz — con `USAGE_MEDIA` el canceller no tenía nada que cancelar, y el audio del altavoz se colaba de vuelta al mic. Se notaba más en "Rápido" porque su `silenceDurationMs` corto y `endOfSpeechSensitivity=HIGH` necesitan mucho menos para registrar un "nuevo turno". Fix: `LiveAudioPlayback` ahora usa `USAGE_VOICE_COMMUNICATION` (mismo camino de audio que la captura, AEC ahora sí recibe la referencia). Efecto secundario que había que prevenir: `USAGE_VOICE_COMMUNICATION` + `MODE_IN_COMMUNICATION` sigue el enrutamiento normal de telefonía, que por defecto va al auricular, no al altavoz — se agregó forzar `audioManager.isSpeakerphoneOn = true` mientras la llamada está activa (restaurado al colgar) para no regresar a un audio casi inaudible por el auricular.
