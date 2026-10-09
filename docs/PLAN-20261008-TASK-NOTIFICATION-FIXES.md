# 📋 PLAN: Notification Deep-Link Reliability + Task Confirmation UX Fixes
> **Plan ID:** `PLAN-20261008-TASK-NOTIFICATION-FIXES`
> **Versión:** `1.0.0`
> **Tipo de Plan:** `BugFix`
> **Estado:** `Creado`

## 🎯 Resumen y Arquitectura

Corregir los 7 hallazgos del análisis E2E del flujo de notificaciones de tareas automatizadas (screens 00–08, logcat S22 Ultra 2026-10-08). El defecto central (BUG-1) es que `MainActivity` tiene launchMode `standard` implícito: cuando la app está viva en background, los PendingIntents de notificación producen `START_TASK_TO_FRONT` (result code=2 verificado en logcat) y el intent — con `EXTRA_CONVERSATION_ID` — se descarta sin llegar a `onNewIntent`. Fix: `android:launchMode="singleTask"` en el manifest (válido: MainActivity es root con `ACTION_MAIN`+`CATEGORY_LAUNCHER`; ya implementa `onNewIntent` → `handleNavigationIntent` → `notificationConversationId` StateFlow → `LaunchedEffect` → `selectConversation`). Este fix repara de golpe 11 superficies de notificación verificadas en el double-check. Los hallazgos restantes: renderizado markdown del card de confirmación, inyección de fecha de ejecución en prompts de tarea (el modelo generó "octubre 2025"), prompt del usuario con data key en plaintext y paso de notificación redundante, descubribilidad del historial de ejecuciones, y pulido del banner.

## 🔍 Alcance (Scope)
- **DENTRO (In):**
  - `app/src/main/AndroidManifest.xml` (launchMode MainActivity)
  - `app/src/main/java/com/newoether/agora/automation/TaskManager.kt` (inyección de fecha en `executeConversationLocked`)
  - `app/src/main/java/com/newoether/agora/ui/automation/TaskConfirmationCard.kt` (markdown)
  - `app/src/main/java/com/newoether/agora/ui/chat/composables/PendingTaskConfirmationsBanner.kt` (orden)
  - `app/src/main/java/com/newoether/agora/ui/tasks/TaskEditorPage.kt` (hint descubribilidad)
  - Prompt de la tarea del usuario (Task Editor, contenido del usuario — texto corregido provisto)
  - Tests unitarios afectados
- **FUERA (Out):**
  - Cambios al pipeline de generación, mailboxes, o `MessageGenerationController`
  - Rediseño del sistema de confirmaciones (F1–F8 ya en master)
  - Proyección limpia de IMAP en HeartbeatPromptBuilder (los snippets crudos son salida del email tool; tocarlo requiere re-timing del prompt del heartbeat — se deja como backlog)
  - Rotación de la data key de OpenWeather (acción del usuario fuera del repo; solo se elimina del prompt)

## 🗺️ Fases de Implementación

### Fase 1: Deep-link reliability (BUG-1) — fix raíz de 1 línea + blindaje
- [ ] **[F1-T1]** `launchMode="singleTask"` en MainActivity
  - **Archivos/Módulos:** `app/src/main/AndroidManifest.xml:55-68`
  - **Acción:** Añadir `android:launchMode="singleTask"` al `<activity android:name=".MainActivity">`. Sin otros cambios: `onNewIntent` ya está implementado y llama `handleNavigationIntent` (que consume `EXTRA_CONVERSATION_ID` y `agora://conversation/{id}`), y `consumeNotificationTarget` (compareAndSet) ya protege contra consumos cruzados. Nota de diseño en un comment del manifest no aplica (XML), documentar en el commit.
  - **Verificación:** Build `gradlew.bat assembleFdroidDebug`; en device: app en background con Heartbeat abierto → tap "Abrir conversación" en la rich confirmation → debe abrir la conversación de la tarea (no Heartbeat). Verificar en logcat: el START ya no es `result code=2` sin entrega; debe aparecer `onNewIntent` → `handleNavigationIntent`.
- [ ] **[F1-T2]** Auditoría de regresión de las 11 superficies (read-only, tabla de verificación)
  - **Archivos/Módulos:** sin cambios de código — checklist documental
  - **Acción:** Confirmar cada punto de entrada contra el nuevo comportamiento: (1) `TaskPromptNotifier.post` acción "Abrir conversación"; (2) `TaskPromptNotifier.postInfo` content tap; (3) `AgoraForegroundService.showTerminalNotification` (agora://conversation + extra — se beneficia, mismo patrón roto); (4) `AutomationForegroundInfo` FGS tap; (5) `HeartbeatNotifier` (sin extras: onNewIntent→handleNavigationIntent setea null, no-op correcto); (6) `LocalModelDownloadWorker`; (7) `AutoBackupManager` (leer flags); (8) `AssistantDeviceToolProvider:657` (device notification tool); (9) `AssistantOverlay.openInApp` desde AssistantActivity — hoy sufre el MISMO bug BUG-1 (NEW_TASK hacia standard con task viva descarta el intent): el fix lo repara; verificar que abre la conversación correcta y Back sale a home (overlay se auto-cerró); (10) `LiveVoiceForegroundService:62`; (11) `TaskConfirmationActivity.openConversation` (hoy funciona por CLEAR_TOP misma task; con singleTask pasa a onNewIntent — debe seguir navegando). Parte estática: tabla con análisis de código por superficie en el checkpoint. Parte dinámica: se consolida en el smoke E2E de F5-T2.
  - **Verificación:** Tabla con resultado por superficie en el reporte de ejecución; las que fallen se corrigen en esta fase antes de continuar.
- [ ] **[F1-T3]** Test de contrato del manifest
  - **Archivos/Módulos:** `app/src/test/java/com/newoether/agora/` (nuevo test junto a los source-contract existentes)
  - **Acción:** Añadir assertions al source-contract test existente que ya lee el manifest (localizar el que ancla canales/notificaciones; si no existe, crear `MainActivityLaunchModeContractTest`): MainActivity declara `singleTask`, sigue `exported`, y conserva `ACTION_MAIN`/`CATEGORY_LAUNCHER` (requisito oficial para singleTask).
  - **Verificación:** `gradlew.bat :app:testFdroidDebugUnitTest --tests "*LaunchMode*"` verde.

### Fase 2: Fecha de ejecución en prompts de tarea (BUG-4, app-side genérico)
- [ ] **[F2-T1]** Inyectar encabezado de fecha/hora en el prompt de ejecución
  - **Archivos/Módulos:** `app/src/main/java/com/newoether/agora/automation/TaskManager.kt:460-481` (`executeConversationLocked`)
  - **Acción:** En el call-site de `engine.runOnceWithAutomationGuardsHeld(userText = task.prompt, ...)`, componer el userText con un encabezado factual de 1 línea: fecha/hora local de ejecución + zona horaria (mismo patrón factual que usa el heartbeat, sin instrucciones). NO modificar `TaskEntity.prompt` persistido (el usuario ve su prompt original en el editor). Extraer a una función pura testeable (`executionPromptHeader(at: Long, zone: ZoneId)` + compose) para respetar la política de pureza de policies.
  - **Verificación:** Nuevo test unitario en `TaskManagerTest`/nuevo test file: el prompt compuesto = header + prompt original; el header contiene la fecha formateada local; `TaskEntity.prompt` inmutable. Suite automation verde.
- [ ] **[F2-T2]** Corregir el prompt de la tarea del usuario (BUG-2 + BUG-5 + refuerzo BUG-4)
  - **Archivos/Módulos:** Contenido en Room (via UI Task Editor) — sin cambios de código
  - **Acción:** Proveer al usuario el texto corregido del prompt: (a) ELIMINAR "Paso 2 — Enviar notificación" completo (la rich confirmation de la app ya notifica; el modelo generó una notificación de device duplicada — "Clima hoy" 17:46); (b) ELIMINAR la "Opción B" con la data key `bf_live_…` en plaintext (persistida en chat Room y exportable sin cifrar en el `.agora`); (c) mantener solo la Opción A (MCP `fetch_data`); (d) quitar la fecha hardcodeada si existe en el texto. El usuario lo pega en el editor de la tarea. Alternativa: script ADB/Room opcional — NO automatizar sin aprobación.
  - **Verificación:** Siguiente ejecución de la tarea: 1 sola notificación de resultado (la confirmation), sin data key en el texto del chat, fecha correcta en el pronóstico.

### Fase 3: Markdown en el card de confirmación (BUG-3)
- [ ] **[F3-T1]** Renderizar `bodyText` como markdown en `TaskConfirmationCard`
  - **Archivos/Módulos:** `app/src/main/java/com/newoether/agora/ui/automation/TaskConfirmationCard.kt:42+`
  - **Acción:** El card (Compose, dentro de `TaskConfirmationActivity`) reemplaza el `Text(bodyText)` plano por el renderer de markdown existente del chat (`MessageItemMarkdown.kt:242` `MarkdownTextContent` — hoy `internal`; exponer vía wrapper público en el mismo archivo o mover la visibilidad). Constrain: altura máxima + `verticalScroll` (el card ya tiene layout colapsable "Mostrar más" — preservar ese estado). La NOTIFICACIÓN (`BigTextStyle`) y el BANNER (2 líneas) mantienen el texto plano — Android no renderiza markdown en notificaciones y el banner es un peek.
  - **Verificación:** Screenshot en device: el pronóstico con tablas `|…|` y `---` renderiza como tabla/separadores; "Mostrar más" expande con scroll; card centrada y abajo ambas correctas. `compileFdroidDebugUnitTestKotlin` sin warnings de visibilidad.
- [ ] **[F3-T2]** Test de no-regresión del texto plano en notificación
  - **Archivos/Módulos:** test existente de TaskPromptNotifier si aplica (localizar); si no, assertion en source-contract
  - **Acción:** Asegurar que `post()` sigue usando `bodyText` plano (BigTextStyle) — el cambio de F3-T1 NO debe tocar `TaskPromptNotifier`.
  - **Verificación:** Suite focused de automation/notifier verde.

### Fase 4: Descubribilidad y banner (BUG-6 + menores)
- [ ] **[F4-T1]** Hint de historial en el Task Editor
  - **Archivos/Módulos:** `app/src/main/java/com/newoether/agora/ui/tasks/TaskEditorPage.kt` (sección historial, cerca de línea 398), `app/src/main/res/values/strings.xml` + 12 locales
  - **Acción:** Una línea de texto supporting bajo el header del historial de ejecuciones: las conversaciones de ejecución viven aquí (tocar para abrir), no en la lista principal de chats (excluidas por diseño: `ChatCoreDao WHERE taskId IS NULL`). String nuevo `task_history_executions_hint` + traducciones ×12.
  - **Verificación:** UI en device muestra el hint; `gradlew.bat lint`/build verde; strings presentes en los 12 locales.
- [ ] **[F4-T2]** Priorizar la fila de la conversación visible en el banner
  - **Archivos/Módulos:** `app/src/main/java/com/newoether/agora/ui/chat/composables/PendingTaskConfirmationsBanner.kt:105-110`
  - **Acción:** Ordenar `rows` para que la confirmación cuya `conversationId` coincide con la conversación abierta aparezca primero (sort estable: match primero, resto por timestamp). NO filtrar — el banner es global por diseño (múltiples orígenes); solo reordenar.
  - **Verificación:** Test unitario nuevo (pure function: orden de filas) + manual: abrir la conversación de la tarea → su fila arriba del banner.

### Fase 5: Gate completo y verificación on-device
- [ ] **[F5-T1]** Gate CI
  - **Acción:** `gradlew.bat -p build-logic test` + `gradlew.bat :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest verifyKotlinFileSize --no-daemon --max-workers=1`
  - **Verificación:** BUILD SUCCESSFUL, 0 fallos.
- [ ] **[F5-T2]** Build + install + smoke E2E
  - **Acción:** `gradlew.bat assembleFdroidDebug` → `adb -s R5CT422CGKH install -r …`. Smoke script: (1) app abierta en Heartbeat, background; (2) "Ejecutar ahora" en la tarea del clima; (3) al llegar la confirmation: tap "Abrir conversación" → debe abrir la conversación de la tarea (registrar logcat: no `result code=2` huérfano); (4) tap "Ver" → card markdown renderizado; (5) verificar historial → reabrir la misma conversación; (6) confirmar UNA notificación de resultado.
  - **Verificación:** Los 6 pasos pasan; capturas de evidencia en `tmp/` si el usuario las solicita.
- [ ] **[F5-T3]** Reporte + actualización de documentation de contrato
  - **Archivos/Módulos:** `development/automation.md` (§ de confirmations, si el launchMode cambia interacción documentada) — evaluar; memory `notification-open-conversation-task-to-front-root-cause` → marcar fix implementado.
  - **Verificación:** Doc revisada; memory actualizada.

## 🧪 Estrategia de Verificación Global
- **Suite de Pruebas:** `gradlew.bat -p build-logic test` y `gradlew.bat :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest verifyKotlinFileSize --no-daemon --max-workers=1`
- **Prueba Manual:** Smoke E2E del F5-T2 en S22 Ultra (R5CT422CGKH) — cubre deep-link desde background (BUG-1), conteo de notificaciones (BUG-2), markdown (BUG-3), fecha (BUG-4), ausencia de key (BUG-5), reapertura desde historial (BUG-6).

## 🔄 Plan de Rollback y Contingencias
- **Estrategia de Reversión:** Cada fase es un commit independiente; revert individual por `git revert`. El fix de manifest (F1-T1) es 1 atributo XML — revert trivial si algún surface muestra regresión (F1-T2 es la red de seguridad: detecta antes del merge).
- **Riesgo singleTask:** activities sobre MainActivity en la misma task se destruyen al recibir el intent (solo `TaskConfirmationActivity`, que ya se auto-finaliza al abrir conversación — sin pérdida). Comportamiento Back/Recents de launcher-root no cambia (doc oficial).
- **Garantías de Datos:** `TaskEntity.prompt` persistido NUNCA se modifica por código (F2-T1 compone en memoria); la corrección del prompt del usuario (F2-T2) es una edición vía UI del propio usuario, revisable antes de guardar.

## ❓ Preguntas Abiertas y Riesgos
- ~~F1: `AssistantOverlay.openInApp`~~ — RESUELTO con evidencia: ese path sufre el mismo BUG-1 hoy (NEW_TASK → standard → intent descartado); el fix lo repara. Caso añadido a F1-T2/F5-T2.
- ~~F2-T2 texto del prompt~~ — RESUELTO: texto final corregido provisto al usuario (elimina Paso 2 de notificación + Opción B con data key; instruye usar fechas de la API). El usuario lo pega vía Task Editor. Pendiente del usuario: rotar la data key de OpenWeather (estuvo en plaintext en Room/export).
- ~~Riesgo residual F3 (visibilidad)~~ — CORREGIDO CON EVIDENCIA: `MarkdownTextContent`/`ChatMarkdownRenderContext` son `internal` Kotlin (module-scoped), y `ui/automation` está en el mismo módulo `:app` — NO se requiere cambio de visibilidad. La complejidad real: el context toma 9 params ensamblados en `MessageBubbleAssets.kt:423`; preferencia = helper privado `rememberConfirmationCardMarkdownContext()` en TaskConfirmationCard con defaults (`parseInlineDollarMath = false`); fallback = wrapper público simple en MessageItemMarkdown.kt.
- La proyección IMAP cruda del heartbeat queda FUERA de alcance (backlog documentado: micro-plan aparte — función pura de proyección From|Asunto|uid en HeartbeatPromptBuilder + test, sin mezclar con este plan).
