# ðŸ“‹ PLAN: Fixes del Inventario Completo de Notificaciones
> **Plan ID:** `PLAN-20261010-NOTIFICATION-FIXES`
> **VersiÃ³n:** `1.0.0`
> **Tipo de Plan:** `BugFix + Hardening`
> **Estado:** `Creado`
> **Origen:** InvestigaciÃ³n `zg` exhaustiva 2026-10-10 â€” 14 archivos con superficies de notificaciÃ³n, 9 superficies reales, 8 hallazgos (H-N1â€¦H-N8)

## ðŸŽ¯ Resumen y Arquitectura

Corregir los 8 hallazgos del inventario completo de notificaciones de Agora. Los crÃ­ticos comparten archivos y se fijan atÃ³micamente (H-N1 colisiÃ³n de ID `1001` entre `HeartbeatNotifier` y `AutoBackupManager` â€” el canal NO aÃ­sla la clave `notify(tag,id)`; H-N2 deep-link muerto `OPEN_HEARTBEAT` que ningÃºn handler consume â€” confirmado por comentario en `TaskPromptNotifier.kt:158-160` y por `MainActivity.handleNavigationIntent:291` que solo lee `EXTRA_CONVERSATION_ID`; H-N3 requestCodes `0` compartidos que el fix H-N2 convertirÃ­a en colisiÃ³n real de PendingIntents). El hallazgo de mayor impacto UX es H-N6 (triple notificaciÃ³n por tarea programada, causa raÃ­z ya diagnosticada: `runOnceWithAutomationGuardsHeld` no expone `suppressTerminalNotification`). El double-check de coverage verificÃ³ ademÃ¡s: settings UI (POST_NOTIFICATIONS pedido en 3 sitios con canales creados antes del prompt â€” sin hallazgos), BootReceiver (H-N8: los retry-post alarms del heartbeat no sobreviven reboot porque `armedReminders()` filtra por `remindAtEpochMs` y el contrato P2 exige que el retry jamÃ¡s toque ese campo).

**Superficies verificadas (9 reales + 1 falso positivo):**

| # | Superficie | Canal | Espacio IDs | Deep-link |
|---|---|---|---|---|
| 1 | `AgoraForegroundService` FGS | `agora_generation_status` (LOW) | `1` | n/a ongoing |
| 2 | `showTerminalNotification` | `agora_completed` (HIGH) | `hash(convId) & 0x7FFFFFFF` | âœ… extra+uri |
| 3 | `AutomationForegroundInfo` | `agora_automation` (LOW) | `0x3500_0000 \| hash24` | âœ… con convId |
| 4 | `TaskPromptNotifier.post` | `task_confirmation_v1` (HIGH) | `51000+hash24` | âœ… card+3 acciones |
| 5 | `TaskPromptNotifier.postInfo` | `task_confirmation_info_v1` | `51000+hash(contenido)` | tap-only |
| 6 | `HeartbeatNotifier` | `heartbeat_notifications` (HIGH) | `1001` âŒ colisiÃ³n | âŒ action muerto |
| 7 | `AutoBackupManager` | `auto_backup` (DEFAULT) | `1001` âŒ colisiÃ³n | âš ï¸ sin target |
| 8 | `AssistantDeviceToolProvider.send_notification` | `assistant_actions` | `10000+counter` âš ï¸ | âš ï¸ sin target |
| 9 | `LocalModelDownloadWorker` / `LiveVoiceFGS` | propios | hash(workSpec) / 424 | n/a |
| FP | `SendAcceptanceNotifier` | â€” | â€” | No es notificaciÃ³n Android (event publisher interno) |

## ðŸ” Alcance (Scope)

- **DENTRO (In):**
  - `service/HeartbeatNotifier.kt` â€” H-N1 (ID), H-N2 (deep-link), H-N3 (requestCode)
  - `data/AutoBackupManager.kt` â€” H-N1 (lado opuesto), H-N5 (tap target)
  - `automation/HeartbeatScheduler.kt` â€” call-site: pasar `heartbeatConversationId` (H-N2)
  - `automation/TaskManager.kt` + `TaskExecutionEngine` â€” H-N6 (`suppressTerminalNotification` en la variante con guards)
  - `service/BootReceiver.kt` + `TaskConfirmationStore` â€” H-N8 (re-arm retry-post)
  - `tool/AssistantDeviceToolProvider.kt` â€” H-N4 (ID durable), H-N5 (convId opcional), H-N7 (cap)
  - Tests de contract (source-contract pattern existente): colisiÃ³n IDs, deep-link, cap, boot re-arm
  - `development/automation.md` + docs user-facing en/zh/zh-Hant â€” si cambia comportamiento visible
- **FUERA (Out):**
  - Pipeline de generaciÃ³n / mailboxes / `MessageGenerationController`
  - RediseÃ±o de confirmaciones ricas (F1â€“F8 en master)
  - Persistencia cross-process del ID de `send_notification` (el cap + hash de contenido es suficiente)
  - Cambios a la UI de settings del permiso POST_NOTIFICATIONS (verificada correcta: 3 sitios, canales antes del prompt, contrato `application-ui.md` Â§19 intacto)

## ðŸ—ºï¸ Fases de ImplementaciÃ³n

### Fase 1: Colisiones crÃ­ticas + deep-link (H-N1 + H-N2 + H-N3) â€” Ã¡tomicos, mismo PR

- [x] **[F1-T1]** Eliminar la colisiÃ³n de ID `1001`
  - **Archivos:** `HeartbeatNotifier.kt:38,66` â€” `NOTIFICATION_ID = 1001` â†’ `2001` (o `NOTIFICATION_ID = "heartbeat_alert".hashCode() and Int.MAX_VALUE`, siguiendo el patrÃ³n `stableCompletionNotificationId`).
  - **Por quÃ© no tocar AutoBackupManager:** heartbeat es la superficie que debe cambiar â€” su ID ademÃ¡s es fijo por diseÃ±o "reemplaza, no apila"; el backup usa el mismo valor por casualidad, no por contrato.
  - **VerificaciÃ³n:** test de contract â€” `HeartbeatNotifier` y `AutoBackupManager` declaran IDs distintos; smoke device: fallo de heartbeat en background + auto-backup posterior â†’ AMBAS notificaciones coexisten en el shade.

- [x] **[F1-T2]** Deep-link del heartbeat al patrÃ³n verificado
  - **Archivos:** `HeartbeatNotifier.kt:46-60` + `HeartbeatScheduler.kt:323,463-464`.
  - **AcciÃ³n:** `sendHeartbeatNotification(title, body, conversationId)` â€” setear `MainActivity.EXTRA_CONVERSATION_ID` + `data = agora://conversation/{id}` + `FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP` (mismo shape exacto que `AgoraForegroundService.createPendingIntent`, que es el patrÃ³n device-verified). Eliminar el action muerto `OPEN_HEARTBEAT`. Call-site: `sendHeartbeatNotification(resultText, heartbeatConversationId)` â€” el scheduler ya resuelve el id en la lÃ­nea 232.
  - **VerificaciÃ³n:** smoke device â€” tap en alerta de heartbeat fallido abre la heartbeat conversation (logcat: `onNewIntent` â†’ `EXTRA_CONVERSATION_ID`); test de contract sobre el source (extra + uri + requestCode propio presentes).

- [x] **[F1-T3]** RequestCode propio para el PendingIntent del heartbeat
  - **Archivos:** `HeartbeatNotifier.kt:52-57`.
  - **AcciÃ³n:** `PendingIntent.getActivity(context, HEARTBEAT_NOTIFICATION_ID, ...)` â€” el requestCode debe derivar del mismo ID de notificaciÃ³n (patrÃ³n id-propio ya usado por `AutomationForegroundInfo:67` y `TaskPromptNotifier`). Sin esto, tras F1-T2 el Intent del heartbeat quedarÃ­a `filterEquals`-idÃ©ntico al del AutoBackup â†’ `FLAG_UPDATE_CURRENT` los pisarÃ­a mutuamente.
  - **VerificaciÃ³n:** cubierto por el contract test de F1-T4; smoke: tap en ambas notificaciones navega a destinos distintos.

- [x] **[F1-T4]** Contract test `NotificationIdDeepLinkContractTest`
  - **Archivos:** nuevo, junto a los source-contract tests existentes (`TaskConfirmationPostRetryContractTest` pattern).
  - **Assertions:** IDs `1001`/`2001` distintos entre los dos archivos; `HeartbeatNotifier` contiene `EXTRA_CONVERSATION_ID` y `agora://conversation/`; NO contiene `OPEN_HEARTBEAT`; requestCode del PendingIntent â‰  `0` literal.

### Fase 2: Triple notificaciÃ³n de tarea programada (H-N6)

- [x] **[F2-T1]** ~~Exponer `suppressTerminalNotification` en la variante con guards~~ â€” **NO-OP (2026-10-10):** ya implementado en commit `29fa7561` ("suppress the duplicate terminal notification for scheduled tasks and final loop cycles", 2026-10-08). Verificado: `TaskExecutionEngine.runOnceWithAutomationGuardsHeld:409` expone el param; `TaskManager:487` pasa `taskConfirmationsEnabled()`; `LoopManager:308-311` pasa `taskConfirmationsEnabled() && !claimed.active` (solo ciclo final). El hallazgo quedÃ³ obsoleto entre el diagnÃ³stico del plan y su ejecuciÃ³n.

### Fase 3: Hardening de `send_notification` + tap de backup (H-N4 + H-N5 + H-N7)

- [x] **[F3-T1]** ID durable por contenido + cap de vivas
  - **Archivos:** `tool/AssistantDeviceToolProvider.kt:655-676`.
  - **AcciÃ³n:** (a) `notificationId = ASSISTANT_NOTIFICATION_ID_BASE + (title+message).hashCode() and 0x00ff_ffff` â€” mismos posts se reemplazan, distintos coexisten, sobrevive process death (mismo patrÃ³n de `postInfo`); (b) cap simple: LRU `LinkedHashSet<Int>` de IDs vivos, mÃ¡x 20 â€” al exceder, `manager.cancel(oldest)`; sin persistencia (el cap es higiene del shade, no un durable contract).
  - **VerificaciÃ³n:** test del provider â€” mismo contenido 2Ã— â†’ 1 notificaciÃ³n (reemplaza); 21 contenidos distintos â†’ el mÃ¡s viejo se cancela.

- [x] **[F3-T2]** `conversationId` opcional en la tool + deep-link
  - **Archivos:** `AssistantDeviceToolProvider.kt` (schema del tool + `sendNotification`).
  - **AcciÃ³n:** param opcional `conversation_id: string`; cuando viene, el PendingIntent setea `EXTRA_CONVERSATION_ID` + `agora://conversation/{id}` (patrÃ³n verificado). Da al modelo control del destino del tap.
  - **VerificaciÃ³n:** tool test con y sin el param.

- [x] **[F3-T3]** Tap del auto-backup con contexto
  - **Archivos:** `data/AutoBackupManager.kt:236-243`.
  - **AcciÃ³n:** el PendingIntent setea requestCode propio (`"auto_backup".hashCode()`-style) y, para fallos, deep-link a la pantalla de backup/automation settings (`agora://` interno si existe un destino, else mantener MainActivity sin extra â€” decidir en implementaciÃ³n con el contract `application-ui.md`).
  - **VerificaciÃ³n:** smoke â€” tap en notificaciÃ³n de backup abre el destino correcto; el requestCode ya no es `0`.

### Fase 4: BootReceiver re-arm de retry-post (H-N8)

- [x] **[F4-T1]** Re-arm de los retry alarms â€” **ejecutado 2026-10-10 con desviaciÃ³n aprobada**: el query es **source-agnostic** (`TaskConfirmationStore.pendingWithoutSnooze()` â†’ DAO `selectPendingWithoutSnooze()`: PENDING con `remindAtEpochMs IS NULL`), no HEARTBEAT-only como el plan original. Motivo: el punto de arming (`stageAndNotifyTaskConfirmation`) es compartido por TASK/LOOP/HEARTBEAT y sin marker durable no se puede distinguir retry-armed de posted-normal; filtrar por HEARTBEAT dejarÃ­a stranded los retries de TASK/LOOP. Los guards del receiver (still-pending/not-foreground/canPost) hacen del re-arm redundante un no-op que re-arma. Pinnado por `TaskConfirmationPostRetryContractTest.boot re-arms retry-post alarms`.

### Fase 5: Docs + Gate

- [x] **[F5-T1]** `development/automation.md` â€” Â§5: ID 1002 + deep-link + requestCode del heartbeat notifier; Â§snooze: bloque "Reboot re-arm is dual" con la desviaciÃ³n source-agnostic documentada.
- [x] **[F5-T2]** Docs user-facing en/zh/zh-Hant â€” nota de fallo extendida: "tapping it opens the heartbeat conversation".
- [x] **[F5-T3]** Gate completo en dos comandos (patrÃ³n verificado) â€” **VERDE 2026-10-10**: build-logic test (36s) + testFdroidDebugUnitTest + testPlayDebugUnitTest + verifyKotlinFileSize (BUILD SUCCESSFUL 8m 8s, 0 FAILED, 995 files / mÃ¡x 1007).

## âœ… Estado Final del Plan

**COMPLETADO 11/11 (2026-10-10).** Commits en master local: `08945767` (F1), `5e744357` (F3), `37f131db` (F4+F5). F2-T1 verificado como no-op (el fix ya existÃ­a en `29fa7561`). Una desviaciÃ³n aprobada durante ejecuciÃ³n: F4-T1 usa query source-agnostic (`pendingWithoutSnooze`) en lugar del HEARTBEAT-only original.

**Pendiente del usuario:** smoke on-device (S22 Ultra) de F1/F4 y autorizaciÃ³n de push.

## âš ï¸ Riesgos y Consideraciones

1. **Fase 1 es atÃ³mica** â€” H-N1/H-N2/H-N3 tocan el mismo archivo y call-site; un fix parcial (solo ID, sin requestCode) introducirÃ­a la colisiÃ³n de PendingIntents que hoy no existe.
2. **H-N6 toca el engine** â€” `runOnceWithAutomationGuardsHeld` es el path de Task/Loop; el default `false` mantiene el comportamiento legacy para cualquier caller que no opte. Correr TODA la suite de automation, no solo el test nuevo.
3. **El FGS "Running task" no se elimina** â€” es obligaciÃ³n de WorkManager (`setForeground`); la triple notificaciÃ³n se reduce de 3â†’2, no a 1. Documentar la expectativa exacta en el smoke.
4. **H-N4 sin persistencia cross-process** â€” decisiÃ³n deliberada: el cap es higiene; persistir IDs de tool-posts aÃ±adirÃ­a estado durable para un problema cosmÃ©tico.
5. **Smoke on-device requerido para Fase 1 y 2** â€” el anÃ¡lisis E2E previo (S22 Ultra, logcat 2026-10-08) demostrÃ³ que los behaviors de entrega de PendingIntents no se pueden validar solo con tests JVM.

## ðŸ“Š Criterios de AceptaciÃ³n

1. Fallo de heartbeat en background + auto-backup concurrente â†’ 2 notificaciones coexisten; el tap del heartbeat abre la heartbeat conversation; el tap del backup abre el destino elegido.
2. Tarea programada en background â†’ exactamente 2 notificaciones (FGS + confirmation); 0 "Agora responded".
3. `send_notification`: contenido repetido reemplaza; >20 vivas no acumula; `conversation_id` opcional navega.
4. `BOOT_COMPLETED` con retry-post armado â†’ alarm re-armada junto al snooze.
5. Gate completo verde en ambos flavors + file size + build-logic.
6. Contract tests nuevos pinnean: IDs distintos, deep-link completo, requestCode propio, re-arm dual.

## ðŸ”„ Orden de EjecuciÃ³n

Fase 1 â†’ Fase 2 â†’ Fase 3 â†’ Fase 4 â†’ Fase 5. Las fases 3 y 4 son independientes entre sÃ­ y pueden paralelizarse; Fase 2 depende solo de la Fase 1 en el sentido de que ambas tocan `HeartbeatScheduler`-adyacentes (no mismo archivo, revisar merge si se paralelizan).
