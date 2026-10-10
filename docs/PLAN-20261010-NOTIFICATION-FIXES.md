# 📋 PLAN: Fixes del Inventario Completo de Notificaciones
> **Plan ID:** `PLAN-20261010-NOTIFICATION-FIXES`
> **Versión:** `1.0.0`
> **Tipo de Plan:** `BugFix + Hardening`
> **Estado:** `Creado`
> **Origen:** Investigación `zg` exhaustiva 2026-10-10 — 14 archivos con superficies de notificación, 9 superficies reales, 8 hallazgos (H-N1…H-N8)

## 🎯 Resumen y Arquitectura

Corregir los 8 hallazgos del inventario completo de notificaciones de Agora. Los críticos comparten archivos y se fijan atómicamente (H-N1 colisión de ID `1001` entre `HeartbeatNotifier` y `AutoBackupManager` — el canal NO aísla la clave `notify(tag,id)`; H-N2 deep-link muerto `OPEN_HEARTBEAT` que ningún handler consume — confirmado por comentario en `TaskPromptNotifier.kt:158-160` y por `MainActivity.handleNavigationIntent:291` que solo lee `EXTRA_CONVERSATION_ID`; H-N3 requestCodes `0` compartidos que el fix H-N2 convertiría en colisión real de PendingIntents). El hallazgo de mayor impacto UX es H-N6 (triple notificación por tarea programada, causa raíz ya diagnosticada: `runOnceWithAutomationGuardsHeld` no expone `suppressTerminalNotification`). El double-check de coverage verificó además: settings UI (POST_NOTIFICATIONS pedido en 3 sitios con canales creados antes del prompt — sin hallazgos), BootReceiver (H-N8: los retry-post alarms del heartbeat no sobreviven reboot porque `armedReminders()` filtra por `remindAtEpochMs` y el contrato P2 exige que el retry jamás toque ese campo).

**Superficies verificadas (9 reales + 1 falso positivo):**

| # | Superficie | Canal | Espacio IDs | Deep-link |
|---|---|---|---|---|
| 1 | `AgoraForegroundService` FGS | `agora_generation_status` (LOW) | `1` | n/a ongoing |
| 2 | `showTerminalNotification` | `agora_completed` (HIGH) | `hash(convId) & 0x7FFFFFFF` | ✅ extra+uri |
| 3 | `AutomationForegroundInfo` | `agora_automation` (LOW) | `0x3500_0000 \| hash24` | ✅ con convId |
| 4 | `TaskPromptNotifier.post` | `task_confirmation_v1` (HIGH) | `51000+hash24` | ✅ card+3 acciones |
| 5 | `TaskPromptNotifier.postInfo` | `task_confirmation_info_v1` | `51000+hash(contenido)` | tap-only |
| 6 | `HeartbeatNotifier` | `heartbeat_notifications` (HIGH) | `1001` ❌ colisión | ❌ action muerto |
| 7 | `AutoBackupManager` | `auto_backup` (DEFAULT) | `1001` ❌ colisión | ⚠️ sin target |
| 8 | `AssistantDeviceToolProvider.send_notification` | `assistant_actions` | `10000+counter` ⚠️ | ⚠️ sin target |
| 9 | `LocalModelDownloadWorker` / `LiveVoiceFGS` | propios | hash(workSpec) / 424 | n/a |
| FP | `SendAcceptanceNotifier` | — | — | No es notificación Android (event publisher interno) |

## 🔍 Alcance (Scope)

- **DENTRO (In):**
  - `service/HeartbeatNotifier.kt` — H-N1 (ID), H-N2 (deep-link), H-N3 (requestCode)
  - `data/AutoBackupManager.kt` — H-N1 (lado opuesto), H-N5 (tap target)
  - `automation/HeartbeatScheduler.kt` — call-site: pasar `heartbeatConversationId` (H-N2)
  - `automation/TaskManager.kt` + `TaskExecutionEngine` — H-N6 (`suppressTerminalNotification` en la variante con guards)
  - `service/BootReceiver.kt` + `TaskConfirmationStore` — H-N8 (re-arm retry-post)
  - `tool/AssistantDeviceToolProvider.kt` — H-N4 (ID durable), H-N5 (convId opcional), H-N7 (cap)
  - Tests de contract (source-contract pattern existente): colisión IDs, deep-link, cap, boot re-arm
  - `development/automation.md` + docs user-facing en/zh/zh-Hant — si cambia comportamiento visible
- **FUERA (Out):**
  - Pipeline de generación / mailboxes / `MessageGenerationController`
  - Rediseño de confirmaciones ricas (F1–F8 en master)
  - Persistencia cross-process del ID de `send_notification` (el cap + hash de contenido es suficiente)
  - Cambios a la UI de settings del permiso POST_NOTIFICATIONS (verificada correcta: 3 sitios, canales antes del prompt, contrato `application-ui.md` §19 intacto)

## 🗺️ Fases de Implementación

### Fase 1: Colisiones críticas + deep-link (H-N1 + H-N2 + H-N3) — átomicos, mismo PR

- [ ] **[F1-T1]** Eliminar la colisión de ID `1001`
  - **Archivos:** `HeartbeatNotifier.kt:38,66` — `NOTIFICATION_ID = 1001` → `2001` (o `NOTIFICATION_ID = "heartbeat_alert".hashCode() and Int.MAX_VALUE`, siguiendo el patrón `stableCompletionNotificationId`).
  - **Por qué no tocar AutoBackupManager:** heartbeat es la superficie que debe cambiar — su ID además es fijo por diseño "reemplaza, no apila"; el backup usa el mismo valor por casualidad, no por contrato.
  - **Verificación:** test de contract — `HeartbeatNotifier` y `AutoBackupManager` declaran IDs distintos; smoke device: fallo de heartbeat en background + auto-backup posterior → AMBAS notificaciones coexisten en el shade.

- [ ] **[F1-T2]** Deep-link del heartbeat al patrón verificado
  - **Archivos:** `HeartbeatNotifier.kt:46-60` + `HeartbeatScheduler.kt:323,463-464`.
  - **Acción:** `sendHeartbeatNotification(title, body, conversationId)` — setear `MainActivity.EXTRA_CONVERSATION_ID` + `data = agora://conversation/{id}` + `FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP` (mismo shape exacto que `AgoraForegroundService.createPendingIntent`, que es el patrón device-verified). Eliminar el action muerto `OPEN_HEARTBEAT`. Call-site: `sendHeartbeatNotification(resultText, heartbeatConversationId)` — el scheduler ya resuelve el id en la línea 232.
  - **Verificación:** smoke device — tap en alerta de heartbeat fallido abre la heartbeat conversation (logcat: `onNewIntent` → `EXTRA_CONVERSATION_ID`); test de contract sobre el source (extra + uri + requestCode propio presentes).

- [ ] **[F1-T3]** RequestCode propio para el PendingIntent del heartbeat
  - **Archivos:** `HeartbeatNotifier.kt:52-57`.
  - **Acción:** `PendingIntent.getActivity(context, HEARTBEAT_NOTIFICATION_ID, ...)` — el requestCode debe derivar del mismo ID de notificación (patrón id-propio ya usado por `AutomationForegroundInfo:67` y `TaskPromptNotifier`). Sin esto, tras F1-T2 el Intent del heartbeat quedaría `filterEquals`-idéntico al del AutoBackup → `FLAG_UPDATE_CURRENT` los pisaría mutuamente.
  - **Verificación:** cubierto por el contract test de F1-T4; smoke: tap en ambas notificaciones navega a destinos distintos.

- [ ] **[F1-T4]** Contract test `NotificationIdDeepLinkContractTest`
  - **Archivos:** nuevo, junto a los source-contract tests existentes (`TaskConfirmationPostRetryContractTest` pattern).
  - **Assertions:** IDs `1001`/`2001` distintos entre los dos archivos; `HeartbeatNotifier` contiene `EXTRA_CONVERSATION_ID` y `agora://conversation/`; NO contiene `OPEN_HEARTBEAT`; requestCode del PendingIntent ≠ `0` literal.

### Fase 2: Triple notificación de tarea programada (H-N6)

- [ ] **[F2-T1]** Exponer `suppressTerminalNotification` en la variante con guards
  - **Archivos:** `TaskExecutionEngine.kt` (`runOnceWithAutomationGuardsHeld`) + `TaskManager.kt` (call-site en `executeConversationLocked` / path de ejecución programada).
  - **Acción:** mismo parámetro default-false que ya existe en `runOnce` (`HeartbeatScheduler.kt:266` lo usa); `TaskManager` pasa `true` para ejecuciones programadas de tasks/loops porque el resultado ya se notifica vía rich confirmations (F8). El FGS "Running task" (#3) NO se toca — es requisito de WorkManager.
  - **Cuidado:** NO suprimir en el path manual "Run now" desde UI con la app en foreground (el usuario está mirando) — el guard existente `appForegroundTracker`/`suppressTerminalNotification` ya cubre solo background; mantener semántica: suprimir el terminal genérico SIEMPRE que la ejecución es automation-scheduled (el confirmation es el canal del resultado).
  - **Verificación:** smoke device — tarea programada con app en background produce exactamente 2 notificaciones (FGS running + rich confirmation), 0 terminal genérico "Agora responded"; tests existentes del pipeline no regresionan (la terminal notification del chat foreground normal queda intacta — ahí no corre el TaskManager).

### Fase 3: Hardening de `send_notification` + tap de backup (H-N4 + H-N5 + H-N7)

- [ ] **[F3-T1]** ID durable por contenido + cap de vivas
  - **Archivos:** `tool/AssistantDeviceToolProvider.kt:655-676`.
  - **Acción:** (a) `notificationId = ASSISTANT_NOTIFICATION_ID_BASE + (title+message).hashCode() and 0x00ff_ffff` — mismos posts se reemplazan, distintos coexisten, sobrevive process death (mismo patrón de `postInfo`); (b) cap simple: LRU `LinkedHashSet<Int>` de IDs vivos, máx 20 — al exceder, `manager.cancel(oldest)`; sin persistencia (el cap es higiene del shade, no un durable contract).
  - **Verificación:** test del provider — mismo contenido 2× → 1 notificación (reemplaza); 21 contenidos distintos → el más viejo se cancela.

- [ ] **[F3-T2]** `conversationId` opcional en la tool + deep-link
  - **Archivos:** `AssistantDeviceToolProvider.kt` (schema del tool + `sendNotification`).
  - **Acción:** param opcional `conversation_id: string`; cuando viene, el PendingIntent setea `EXTRA_CONVERSATION_ID` + `agora://conversation/{id}` (patrón verificado). Da al modelo control del destino del tap.
  - **Verificación:** tool test con y sin el param.

- [ ] **[F3-T3]** Tap del auto-backup con contexto
  - **Archivos:** `data/AutoBackupManager.kt:236-243`.
  - **Acción:** el PendingIntent setea requestCode propio (`"auto_backup".hashCode()`-style) y, para fallos, deep-link a la pantalla de backup/automation settings (`agora://` interno si existe un destino, else mantener MainActivity sin extra — decidir en implementación con el contract `application-ui.md`).
  - **Verificación:** smoke — tap en notificación de backup abre el destino correcto; el requestCode ya no es `0`.

### Fase 4: BootReceiver re-arm de retry-post (H-N8)

- [ ] **[F4-T1]** Re-arm de los retry alarms del heartbeat
  - **Archivos:** `service/BootReceiver.kt:48-53` + `data/TaskConfirmationStore.kt` (nuevo query) + `service/TaskPromptNotifier.kt` (exponer lo que necesite el re-arm).
  - **Acción:** hoy solo se re-arman snooze reminders vía `armedReminders()` (filtra `remindAtEpochMs != null`). Añadir query complementario de filas HEARTBEAT `PENDING` con retry armado (`remindAtEpochMs IS NULL AND status='PENDING' AND sourceType='HEARTBEAT'`) y re-llamar `schedulePostRetry(row.id)`. Sin migration (usa columnas existentes). Respeta el contrato P2: el retry jamás toca `remindAtEpochMs`.
  - **Verificación:** test — tras `BOOT_COMPLETED` con una fila PENDING sin snooze, el re-arm invoca `schedulePostRetry`; con fila snoozeada, invoca `scheduleReminder` (ambos, no solo uno).

### Fase 5: Docs + Gate

- [ ] **[F5-T1]** `development/automation.md` — documentar el ID y deep-link del heartbeat notifier en §5 outcome semantics (hoy el contrato solo describe la notificación de fallo, no su ID/tap); documentar el re-arm de retry-post junto al snooze re-arm.
- [ ] **[F5-T2]** Docs user-facing `docs/en/automation.md` + zh + zh-Hant — nota en Heartbeat: "tapping the failure alert opens the heartbeat conversation" (1 línea por idioma).
- [ ] **[F5-T3]** Gate completo en dos comandos (patrón verificado):
  ```
  gradlew.bat -p build-logic test --no-daemon --max-workers=1
  gradlew.bat :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest verifyKotlinFileSize --no-daemon --max-workers=1
  ```

## ⚠️ Riesgos y Consideraciones

1. **Fase 1 es atómica** — H-N1/H-N2/H-N3 tocan el mismo archivo y call-site; un fix parcial (solo ID, sin requestCode) introduciría la colisión de PendingIntents que hoy no existe.
2. **H-N6 toca el engine** — `runOnceWithAutomationGuardsHeld` es el path de Task/Loop; el default `false` mantiene el comportamiento legacy para cualquier caller que no opte. Correr TODA la suite de automation, no solo el test nuevo.
3. **El FGS "Running task" no se elimina** — es obligación de WorkManager (`setForeground`); la triple notificación se reduce de 3→2, no a 1. Documentar la expectativa exacta en el smoke.
4. **H-N4 sin persistencia cross-process** — decisión deliberada: el cap es higiene; persistir IDs de tool-posts añadiría estado durable para un problema cosmético.
5. **Smoke on-device requerido para Fase 1 y 2** — el análisis E2E previo (S22 Ultra, logcat 2026-10-08) demostró que los behaviors de entrega de PendingIntents no se pueden validar solo con tests JVM.

## 📊 Criterios de Aceptación

1. Fallo de heartbeat en background + auto-backup concurrente → 2 notificaciones coexisten; el tap del heartbeat abre la heartbeat conversation; el tap del backup abre el destino elegido.
2. Tarea programada en background → exactamente 2 notificaciones (FGS + confirmation); 0 "Agora responded".
3. `send_notification`: contenido repetido reemplaza; >20 vivas no acumula; `conversation_id` opcional navega.
4. `BOOT_COMPLETED` con retry-post armado → alarm re-armada junto al snooze.
5. Gate completo verde en ambos flavors + file size + build-logic.
6. Contract tests nuevos pinnean: IDs distintos, deep-link completo, requestCode propio, re-arm dual.

## 🔄 Orden de Ejecución

Fase 1 → Fase 2 → Fase 3 → Fase 4 → Fase 5. Las fases 3 y 4 son independientes entre sí y pueden paralelizarse; Fase 2 depende solo de la Fase 1 en el sentido de que ambas tocan `HeartbeatScheduler`-adyacentes (no mismo archivo, revisar merge si se paralelizan).
