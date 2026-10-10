# 📋 PLAN: Heartbeat Additions — Standing Instructions en Cada Heartbeat
> **Plan ID:** `PLAN-20261009-HEARTBEAT-ADDITIONS`
> **Versión:** `1.0.0`
> **Tipo de Plan:** `Feature (port desde Kai)`
> **Estado:** `Creado`

## 🎯 Resumen y Arquitectura

Port del concepto "Heartbeat Additions" de Kai: tasks con trigger `on_heartbeat` cuyos prompts se inyectan en **cada** heartbeat como standing instructions, renderizados bajo una sección `## Heartbeat Additions` del prompt. En Kai son tasks first-class (editables/cancelables en la UI de tasks, creadas por el modelo vía `schedule_task(on_heartbeat=true)`). En Agora hoy un usuario que quiere "revisa el clima cada heartbeat" solo tiene dos opciones deficientes: (a) una tarea cron con el mismo intervalo que el heartbeat — duplica el scheduling, compite por el conversation lease, y no hereda las secciones de contexto (previous results, email status, response rule); o (b) reescribir el custom prompt completo — pierde el default prompt de self-check y no es un objeto gestionable.

**Decisión de diseño central:** un Heartbeat Addition NO crea un pipeline paralelo. Reutiliza el `TaskEntity` existente con un nuevo valor de trigger (`HEARTBEAT`), la partición ocurre en el prompt builder (puro, testeable), y la ejecución es el heartbeat run mismo — no hay segunda generación. Esto respeta `development/README.md` §Universal Prohibited ("Never create parallel generation pipeline for a feature") y §4.5 ("Prefer generic rules driven by durable fields over feature names").

**Referencias Kai (verificadas 2026-10-09):**
- `composeApp/src/commonMain/.../data/ScheduledTask.kt:56` — trigger HEARTBEAT, PENDING hasta cancelación
- `composeApp/src/commonMain/.../tools/SchedulingTools.kt:56-67` — `on_heartbeat` param, exclusividad de triggers
- `composeApp/src/commonMain/.../data/HeartbeatPromptBuilder.kt` §Heartbeat Additions — "respond with your acknowledgement rather than HEARTBEAT_OK (the additions are the attention)"
- `docs/features/heartbeat.md` §Prompt Building item 2 + `docs/features/tasks.md` §Heartbeat-triggered tasks

## 🔍 Alcance (Scope)

- **DENTRO (In):**
  - `TaskEntity` + Room migration: nuevo campo `trigger` (`TIME` | `HEARTBEAT`; los schedules existentes son todos TIME)
  - `TaskManager`: partición enabled/HEARTBEAT additions para el builder; additions excluidas del scheduling AlarmManager/WorkManager
  - `HeartbeatPromptBuilder`: sección `## Heartbeat Additions` (descripción, id, prompt por item; ack-not-OK cuando hay additions)
  - `Response Rule`: ajuste de condición — additions presentes también la activan (un HEARTBEAT_OK erróneo consume el snapshot igual)
  - Tool `create_task`/`delete_task` (si expuesta): param `on_heartbeat` — gated por el toggle "Access Tasks and Loops" existente
  - UI Task Editor: selector de tipo (One-time/Daily/…/On every heartbeat); additions visibles en la lista de tasks con badge
  - Import/export `.agora`: campo `trigger` en la categoría tasks
  - Contract doc `development/automation.md` §7 + docs user-facing (en/zh/zh-Hant)
  - Tests: builder (sección, orden, ack), TaskManager partición, migration, tool param, import/export
- **FUERA (Out):**
  - Ejecución separada de additions (corren DENTRO del heartbeat run, no como runs propios)
  - Historial de ejecuciones por addition (el heartbeat log ya cubre el run completo)
  - Port del bug de Kai: NO copiar su consume de cola completa ni su falta de in-flight guard (Agora ya los tiene)

## 🗺️ Fases de Implementación

### Fase 1: Modelo + persistencia
- [ ] **[F1-T1]** Campo `trigger` en `TaskEntity` + migration Room (todos los rows existentes → `TIME`)
  - **Verificación:** migration test (schema v N→N+1); lista de tasks sin cambios visibles tras upgrade.
- [ ] **[F2-T1]** `TaskManager.getEnabledHeartbeatAdditions(): List<TaskEntity>` — partición pura; additions jamás entran al `AutomationScheduler`
  - **Verificación:** unit test — una addition nunca produce un alarm/work schedule; partición por `trigger == HEARTBEAT && enabled`.

### Fase 2: Prompt + ejecución
- [ ] **[F2-T1]** Sección `## Heartbeat Additions` en `HeartbeatPromptBuilder` (tras el base prompt, antes de tasks&loops): cada item con description + id + prompt; texto de sección: "address each; if all satisfied and nothing else needs attention, respond with your acknowledgement rather than HEARTBEAT_OK — the additions are the attention"
  - **Verificación:** builder test — sección omitida sin additions; render con id/descripción/prompt; posición en el orden de secciones.
- [ ] **[F2-T2]** `Response Rule` se emite también cuando hay additions activas (aunque no haya SMS/emails/notifs) — el ack vs HEARTBEAT_OK debe quedar inequívoco
  - **Verificación:** builder test — addition presente + sin pendings → Response Rule presente.
- [ ] **[F3-T1]** `HeartbeatScheduler.buildPrompt` alimenta la partición (getEnabledHeartbeatAdditions) — sin cambios al consume/snapshot (additions no son items pendientes)
  - **Verificación:** scheduler harness test — prompt capturado contiene la sección con el prompt de la addition.

### Fase 3: Tool + UI + portabilidad
- [ ] **[F3-T1]** Param `on_heartbeat` en la tool de creación de tasks (default false); validación de exclusividad con schedules
  - **Verificación:** tool test — creación con on_heartbeat produce trigger HEARTBEAT, sin nextRunAt; mezclar on_heartbeat+cron rechazado.
- [ ] **[F3-T2]** Task Editor UI: opción "On every heartbeat" en el selector de schedule; badge en la lista
  - **Verificación:** screenshot/manual — crear, editar, deshabilitar, cancelar una addition.
- [ ] **[F3-T3]** Import/export `.agora` round-trip del campo trigger
  - **Verificación:** export/import test — una addition sobrevive el ciclo completa.

### Fase 4: Docs + gate
- [ ] **[F4-T1]** `development/automation.md` §7 (nueva sección en el orden) + §1 owners; docs user-facing en/zh/zh-Hant bajo Heartbeat
- [ ] **[F4-T2]** Gate completo: `gradlew.bat -p build-logic test :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest verifyKotlinFileSize --no-daemon --max-workers=1`

## ⚠️ Riesgos y Consideraciones

1. **Room migration** — el único cambio de schema; probar contra installs reales (backup/restore).
2. **Prompt bloat** — N additions × prompt largo: cap de additions en el prompt (proponer 10, mismo espíritu que los caps existentes) + `HeartbeatShownLimits`-style constante compartida si alguna vez se consume algo.
3. **ChatSystemPromptBuilder guard** — mantener la regla "heartbeat schedule es user-controlled": una addition no puede habilitar/deshabilitar el heartbeat mismo; el texto de la sección debe reiterarlo.
4. **Toggle global de scheduling** — additions solo llegan al prompt si el toggle de automation está on (heredan el gate del heartbeat run mismo, sin gate extra).
5. **Agora vs Kai** — NO portear el flujo de Kai donde el output de scheduled tasks aterriza en la heartbeat conversation (Agora respeta la propiedad de conversation por task; la continuidad ya viene de Previous Results).

## 📊 Criterios de Aceptación

1. Un usuario puede crear una addition desde la UI y vía tool (toggle on) y verla correr en cada heartbeat con las secciones de contexto presentes.
2. `HEARTBEAT_OK` nunca suprime un run con additions activas si las additions requieren ack (texto de sección + Response Rule).
3. Deshabilitar/cancelar la addition la remueve del próximo heartbeat sin reinicio.
4. Backups `.agora` preservan additions; restores en installs limpios las recrean.
5. Gate completo verde.
