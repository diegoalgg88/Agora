# 📋 PLAN: Unified Tools Settings Page for Agora
> **Plan ID:** `PLAN-20260912-UNIFIED-TOOLS-PAGE`
> **Versión:** `1.0.0`
> **Tipo de Plan:** `Feature`
> **Estado:** `Creado`

## 🎯 Resumen y Arquitectura
Implementar una página unificada "Tools" en los settings de Agora (similar a la pestaña "Tools" de Kai) que agrupe todos los tool providers disponibles: Shell, Web Search, Semantic Search, MCP, Skills, Memory, Image Gen, SMS, Notifications, Heartbeat, y Automation. La página presentará una cuadrícula de tarjetas con el nombre, descripción y toggle de habilitación para cada herramienta. Se añadirá como nueva entrada en el menú principal de settings al mismo nivel que MCP, Shell, Automation, etc.

Decisiones clave:
- Reutilizar `SettingsRepository` existente para leer/escribir estados de habilitación de cada herramienta. **Verificado en código:** todos los estados necesarios ya existen (`imageGenEnabled`, `accessPastConversations`, `accessSavedMemories`, `accessActiveMemory`, `accessSkills`, `accessSkillsModify`, `webSearchEnabled`, `shellEnabled`, `automationToolsEnabled`, `heartbeatEnabled`, `smsReadEnabled`/`smsSendEnabled`, `notificationsEnabled`). No hay estados faltantes en `SettingsRepository`, solo nombres distintos a los asumidos originalmente (ver Fase 1, F1-T1).
- Nueva página `SettingsToolsPage.kt` siguiendo el patrón de otras páginas de settings (CollapsingSettingsScaffold + SettingsGroupColumn + SettingsItem con Switch)
- Para herramientas con páginas propias (Shell, Web Search, MCP, Memory, Skills, Search, Image Gen, Automation), la nueva página Tools será un "dashboard" unificado con enlaces a sus páginas de configuración detallada. **Importante:** SMS, Notifications y Heartbeat NO tienen página propia — son secciones (`SmsSection`, `NotificationsSection`, `HeartbeatSection`) dentro de `SettingsAutomationPage.kt`; su "Configurar" debe navegar a la categoría `"automation"`, no a una página inexistente.
- Solo la subsección **Local Sandbox** de Shell (ejecución local vía Alpine/proot, gateada por `viewModel.isSandboxFlavor`) es exclusiva de fdroid. El toggle `shellEnabled` y los dispositivos SSH remotos funcionan en **ambos** flavors — la tarjeta de Shell en Tools debe mostrarse siempre, no solo en fdroid. SMS y Notifications sí son fdroid-only en su totalidad (gateadas por `smsReaderSupported`/`notificationListenerSupported`).
- La página respeta el límite de 999 líneas por archivo (dividir en secciones si es necesario, como ya se hizo con `SettingsAutomationNotificationsSection.kt`, extraído de `SettingsAutomationPage.kt` por el mismo motivo)

## 🔍 Alcance (Scope)
- **DENTRO (In):**
  - Crear `SettingsToolsPage.kt` con grid de herramientas toggleables
  - Añadir categoría "tools" en `SettingsScreen.kt` (baseSettingsGroups)
  - Añadir string resources en un nuevo `tools_strings.xml` (11 locales reales + base) para título y descripción de la entrada; reutilizar strings ya existentes para cada herramienta (ver F1-T2)
  - Mapear los estados de habilitación ya existentes en `SettingsRepository` a cada tarjeta (Image Gen, Memory, Conversation/Semantic Search, Skills) — verificado que ninguno falta realmente (ver Fase 1, F1-T1)
  - Conectar toggles a los setters existentes en `SettingsRepository` / `SettingsManager`
- **FUERA (Out):**
  - Implementar nuevos tool providers que no existen en Agora (Email, Process Manager, Set Alarm, Create Calendar Event, Open File, Fetch URL) — son features separados
  - Cambiar la lógica de ejecución de tools (ya funciona via ToolProvider)
  - Migrar settings existentes — solo añadir punto de acceso unificado

## 🗺️ Fases de Implementación

### Fase 1: Cimientos y Modelo de Datos
- [ ] **[F1-T1]** Mapear los estados de habilitación reales de cada tarjeta (no requiere nuevos campos en SettingsRepository)
  - **Archivos/Módulos:** `app/src/main/java/com/newoether/agora/data/repository/SettingsRepository.kt` (solo lectura — todos los campos ya existen, verificado leéndolo completo)
  - **Hallazgo (verificado en código, no asumido):** los 4 estados que la versión original de este plan asumía "faltantes" ya existen, con nombres distintos:
    - **Image Gen** → `imageGenEnabled` / `setImageGenEnabled()`. Ya tiene UI y Switch en `SettingsImageGenPage.kt`.
    - **Conversation/Semantic Search** → `accessPastConversations` / `setAccessPastConversations()`. Ya tiene UI en `SettingsSearchPage.kt` (grupo "Access") y ya gatea la exposición de herramientas: `RagToolProvider.definitions()` empieza con `if (!ctx.accessPastConversations) return emptyList()`. El repositorio también expone `ragSearchEnabled`/`setRagSearchEnabled()`, pero **no está conectado a ninguna UI ni a ninguna lógica de gating** (no aparece en `SettingsSearchPage.kt` ni en `RagToolProvider.kt`) — es un campo huérfano; la tarjeta de Tools debe usar `accessPastConversations`, no `ragSearchEnabled`.
    - **Skills** → `accessSkills` / `setAccessSkills()`. Ya tiene UI en `SettingsSkillsPage.kt` y gatea `SkillToolProvider` vía `ctx.skillReadAccess`. Existe además `accessSkillsModify` (gatea `ctx.skillModifyAccess`) sin UI propia — decidir en F2-T2 si se expone.
    - **Memory** → no existe un flag único "memoryEnabled". Son dos flags independientes, ambos con UI en `SettingsMemoryPage.kt` y ambos usados por separado en `MemoryToolProvider.definitions()`: `accessSavedMemories` (lista/crea/edita/borra archivos) y `accessActiveMemory` (actualiza memoria activa). La tarjeta de Memory en Tools debe decidir cómo representar dos flags con un solo Switch (ver F2-T2).
  - **Verificación:** no se requiere ninguna migración de datos ni `StateFlow` nuevo; compilar y confirmar que `viewModel.settings.imageGenEnabled`, `accessPastConversations`, `accessSkills`, `accessSavedMemories`, `accessActiveMemory` están disponibles en Compose (ya lo están)

- [ ] **[F1-T2]** Añadir string resources — solo las 2 realmente nuevas; reutilizar el resto
  - **Locales reales del proyecto (verificado listando `app/src/main/res/`):** `values` (base) + `values-ar`, `values-de`, `values-es`, `values-fr`, `values-ja`, `values-ko`, `values-pt-rBR` (NO `values-pt`), `values-ru`, `values-vi` (faltaba en la versión original de este plan), `values-zh`, `values-zh-rTW`. **No existe `values-it`** (italiano no está soportado en este proyecto) — eliminado del alcance.
  - **Convención del proyecto (verificada):** los strings NO viven todos en `strings.xml`; están repartidos en archivos temáticos que se replican por locale: `strings.xml`, `automation_strings.xml`, `mcp_strings.xml`, `sandbox_shared_strings.xml`, `tool_streaming_strings.xml`, `view_image_strings.xml` (los 6 existen en `values/` y en cada locale, p.ej. `values-es/automation_strings.xml`). Los nuevos strings de la página Tools deben ir en un archivo nuevo `tools_strings.xml` siguiendo el mismo patrón, creado en `values/` y en los 11 locales listados arriba.
  - **Strings realmente nuevas (van en `tools_strings.xml`):** `settings_tools` (título de la entrada en el menú principal) y `settings_tools_desc` (descripción de esa entrada). Nada más es necesario.
  - **Strings a REUTILIZAR sin duplicar (ya existen, verificado en `strings.xml`/`mcp_strings.xml`/`automation_strings.xml`):** `settings_web_search`/`settings_web_search_desc`, `shell_title`/`shell_desc`, `mcp_title`/`mcp_desc`, `search_title`/`search_desc` (ya representa "Conversation/Semantic Search"), `settings_skills`/`settings_skills_desc`, `settings_memory`/`settings_memory_desc`, `settings_image_gen`/`settings_image_gen_desc`, `settings_automation`/`settings_automation_desc`, `automation_heartbeat`+`heartbeat_enabled_desc`, `automation_sms`+`sms_read_enabled_desc`, `automation_notifications`+`notifications_read_enabled_desc`.
  - **Verificación:** `gradlew.bat verifyKotlinFileSize` pasa; no errores de recurso faltante; confirmar que `tools_strings.xml` existe en los 11 locales antes de compilar release (el proyecto empaqueta todos los idiomas siempre — ver `bundle { language { enableSplit = false } }` en `app/build.gradle.kts`)

### Fase 2: Implementación de SettingsToolsPage
- [ ] **[F2-T1]** Crear `SettingsToolsPage.kt` con estructura base
  - **Archivos/Módulos:** `app/src/main/java/com/newoether/agora/ui/settings/SettingsToolsPage.kt`
  - **Acción:** Crear composable `SettingsToolsPage(viewModel, onBack)` usando `CollapsingSettingsScaffold`. Estructura: `SettingsGroupColumn` con un `SettingsGroup` por categoría (Core Tools, Device Tools, Automation Tools, Advanced Tools). Cada tool = `SettingsItem` con icono, título, descripción, Switch y click handler.
  - **Verificación:** Preview en Android Studio / compilar sin errores

- [ ] **[F2-T2]** Implementar sección "Core Tools" (siempre disponibles)
  - **Archivos/Módulos:** `SettingsToolsPage.kt`
  - **Herramientas y su estado real (no `<tool>Enabled` genérico — cada una tiene su propio nombre, ver F1-T1):** Shell (`shellEnabled`), Web Search (`webSearchEnabled`), Conversation/Semantic Search (`accessPastConversations`), MCP (sin flag global de habilitación — mostrar como enlace con conteo de `mcpServers.value.size`, sin Switch), Skills (`accessSkills`), Memory (Switch maestro que escribe `accessSavedMemories` + `accessActiveMemory` a la vez), Image Gen (`imageGenEnabled`)
  - **Acción:** Para cada tool con flag propio, leer el StateFlow real listado arriba y conectar `onCheckedChange` al setter correspondiente (`setShellEnabled`, `setWebSearchEnabled`, `setAccessPastConversations`, `setAccessSkills`, `setImageGenEnabled`; para Memory llamar `setAccessSavedMemories` + `setAccessActiveMemory` juntos). Añadir trailing icon/texto "Configurar" que navega a la página detallada existente (Shell → `SettingsShellPage`, Web Search → `SettingsWebSearchPage`, Search → `SettingsSearchPage`, MCP → `SettingsMcpPage`, Skills → `SettingsSkillsPage`, Memory → `SettingsMemoryPage`, Image Gen → `SettingsImageGenPage`).
  - **Verificación:** Toggles funcionan y persisten; navegación a páginas detalle funciona; definir cómo se muestra el Switch de Memory cuando `accessSavedMemories` y `accessActiveMemory` difieren entre sí (recomendado: "on" solo si ambos lo están)

- [ ] **[F2-T3]** Implementar sección "Device Tools" (solo fdroid)
  - **Archivos/Módulos:** `SettingsToolsPage.kt`
  - **Herramientas:** SMS, Notifications
  - **Acción:** Usar `viewModel.settings.smsReaderSupported` y `notificationListenerSupported` para mostrar/ocultar condicionalmente. Conectar toggles a `smsReadEnabled`/`smsSendEnabled` y `notificationsEnabled`. **Importante (verificado en `SettingsAutomationPage.kt`):** estos toggles NO llaman directamente al setter — activar Read/Send SMS dispara `ActivityResultContracts.RequestPermission()` (READ_SMS/SEND_SMS) y solo persiste el flag si el permiso es concedido; activar Notifications abre `ACTION_NOTIFICATION_LISTENER_SETTINGS` vía launcher. La tarjeta de Tools debe reproducir el mismo flujo de permisos, no un `onCheckedChange` directo al setter. Mostrar estado de permisos (como en SettingsAutomationPage). "Configurar" navega a la categoría `"automation"` (no existe página propia de SMS/Notifications).
  - **Verificación:** En fdroid se ven y funcionan con el mismo comportamiento de permisos que en Automation; en play no aparecen

- [ ] **[F2-T4]** Implementar sección "Automation Tools"
  - **Archivos/Módulos:** `SettingsToolsPage.kt`
  - **Herramientas:** Heartbeat, Automation (Tasks/Loops)
  - **Acción:** Heartbeat usa `heartbeatEnabled`/`setHeartbeatEnabled()`. Automation usa `automationToolsEnabled`/`setAutomationToolsEnabled()` (controla si el modelo puede crear/gestionar tareas y loops; strings existentes `automation_ai_tools`/`automation_ai_tools_desc`). **Corrección:** Daemon Mode (`daemonEnabled`), Exact Execution (`exactExecutionEnabled`) y Wake Lock (`automationWakeLockEnabled`) son configuración de fiabilidad de ejecución en segundo plano, NO exposición de herramientas al modelo — no deben aparecer como tarjetas de "Tools", solo quedan accesibles vía "Configurar". Ninguna de las dos herramientas (Heartbeat, Automation) tiene página propia — "Configurar" navega a la categoría `"automation"` en ambos casos.
  - **Verificación:** Toggles reflejan estado real; enlace a la categoría `automation` funciona para ambas tarjetas

- [ ] **[F2-T5]** Añadir indicadores visuales de estado (permisos, sandbox, etc.)
  - **Archivos/Módulos:** `SettingsToolsPage.kt`
  - **Acción:** Para Shell: mostrar si el Local Sandbox está instalado (solo si `viewModel.isSandboxFlavor`) o el número de dispositivos remotos configurados (`shellDevices.value.size`, disponible en ambos flavors). Para MCP: mostrar count de servers configurados (`mcpServers.value.size`). Para Skills: mostrar count instaladas (vía `skillManager.catalogRevision`/`listFiles()`). Para Memory: mostrar count de archivos guardados (vía `memoryManager.catalogRevision`/`listFiles()`). Para SMS/Notifications: mostrar estado de permisos (granted/denied).
  - **Verificación:** Indicadores se actualizan en tiempo real al cambiar permisos, instalar sandbox, o agregar/quitar archivos

### Fase 3: Integración en Navegación Principal
- [ ] **[F3-T1]** Añadir categoría "tools" en `SettingsScreen.kt`
  - **Archivos/Módulos:** `app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt`
  - **Acción:** En `baseSettingsGroups`, grupo `settings_group_tools`, añadir `SettingsCategory("tools", R.string.settings_tools, R.string.settings_tools_desc, Icons.Default.Build)` (o icono apropiado). Añadir case `"tools" -> SettingsToolsPage(viewModel, onBack = { selectedCategory = null })` en el when.
  - **Verificación:** Nueva entrada aparece en lista principal de settings; click navega a SettingsToolsPage

- [ ] **[F3-T2]** Ajustar icono y orden si necesario
  - **Archivos/Módulos:** `SettingsScreen.kt`
  - **Acción:** Revisar icono (Icons.Default.Build vs Icons.Default.Construction vs custom). Ajustar orden en grupo tools para que "tools" quede primero o en posición lógica.
  - **Verificación:** UI coherente con diseño Material 3

### Fase 4: Verificación y QA
- [ ] **[F4-T1]** Verificación de compilación y tests
  - **Comando:** `gradlew.bat -p build-logic test :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest verifyKotlinFileSize --no-daemon --max-workers=1 --stacktrace`
  - **Verificación:** Build exitoso, 0 tests fallando, source size policy OK

- [ ] **[F4-T2]** Prueba manual en dispositivo (fdroid debug)
  - **Pasos:**
    1. `gradlew.bat assembleFdroidDebug`
    2. `adb install -r app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk`
    3. Abrir Settings → verificar entrada "Tools" en grupo Tools
    4. Entrar a Tools → verificar 7 core tools + 2 device tools + 2 automation tools
    5. Toggle cada tool → verificar persistencia al salir/entrar
    6. Click "Configurar" en Shell/MCP/Skills → navega a página detalle
    7. Verificar fdroid vs play (device tools ocultos en play)
  - **Verificación:** Comportamiento esperado en todos los casos

- [ ] **[F4-T3]** Verificar source size policy (≤999 líneas por archivo)
  - **Comando:** `gradlew.bat verifyKotlinFileSize`
  - **Verificación:** Pasa sin errores; si SettingsToolsPage.kt > 999 líneas, dividir en sub-componentes

## 🧪 Estrategia de Verificación Global
- **Linter & Formateador:** `gradlew.bat ktlintCheck` (si existe) o verificación manual de estilo Kotlin
- **Suite de Pruebas:** `gradlew.bat test` (unit tests fdroid + play)
- **Source Size Policy:** `gradlew.bat verifyKotlinFileSize`
- **Prueba Manual:** Ver pasos en F4-T2 arriba

## 🔄 Plan de Rollback y Contingencias
- **Estrategia de Reversión:** `git revert` del commit que añade la feature. Los cambios son aditivos (nuevo archivo + strings + entrada en menú) sin modificar lógica existente de otros tools.
- **Garantías de Datos:** No hay migración de datos ni nuevos keys en DataStore — todos los flags (Image Gen, Memory, Semantic Search, Skills, etc.) ya existían antes de este plan con sus propios defaults (ver sección de Preguntas Abiertas).

## ❓ Preguntas Abiertas y Riesgos
- **Icono para "Tools":** ¿`Icons.Default.Build`, `Icons.Default.Construction`, `Icons.Default.Engineering`, o crear drawable custom? Recomiendo `Icons.Default.Build` (llave inglesa) por coherencia con "herramientas".
- **Defaults reales (CORREGIDO):** ya no se crean toggles nuevos, así que no hay "default a definir". Los defaults ya fijados en `SettingsRepository` son: `imageGenEnabled = false`, `webSearchEnabled = false`, `shellEnabled = false`, `accessSavedMemories = true`, `accessActiveMemory = true`, `accessPastConversations = true`, `accessSkills = true`, `accessSkillsModify = true`, `automationToolsEnabled = false`, `heartbeatEnabled = true`. La tarjeta de Tools debe reflejar estos valores reales al primer render, no asumir `true` para todo.
- **`ragSearchEnabled` huérfano:** el repositorio expone `ragSearchEnabled`/`setRagSearchEnabled()` pero ningún `ToolProvider` ni página de settings lo lee (verificado buscando en `RagToolProvider.kt` y `SettingsSearchPage.kt`). Este plan usa `accessPastConversations` para la tarjeta de Search y deja `ragSearchEnabled` fuera de alcance; limpiarlo o darle uso es una decisión aparte, no de este plan.
- **`accessSkillsModify` sin UI:** gatea `ctx.skillModifyAccess` en `SkillToolProvider` pero no tiene Switch en `SettingsSkillsPage.kt`. Decidir si la tarjeta de Skills en Tools expone un segundo control o se deja con su valor persistido (`true` por defecto).
- **Duplicación con SettingsAutomationPage (CORREGIDO):** no aplica como se planteaba — Heartbeat, SMS, Notifications y "Automation Tools" no tienen página propia, viven únicamente dentro de `SettingsAutomationPage.kt`. La tarjeta de Tools solo necesita mostrar el estado y enlazar a la categoría `"automation"` existente; no hay UI duplicada que resolver.
- **Fdroid vs Play para Shell (CORREGIDO):** el toggle de Shell y los dispositivos remotos SSH funcionan en ambos flavors (verificado en `SettingsShellPage.kt`: solo el bloque `if (viewModel.isSandboxFlavor)` para Local Sandbox es condicional). No ocultar la tarjeta de Shell completa en Play.
- **Source size:** Si SettingsToolsPage.kt crece > 999 líneas, dividir en `ToolsCoreSection.kt`, `ToolsDeviceSection.kt`, `ToolsAutomationSection.kt` como archivos separados (mismo patrón ya usado por `SettingsAutomationNotificationsSection.kt`, extraído de `SettingsAutomationPage.kt` por el mismo motivo).
- **Strings en 11 locales reales (CORREGIDO):** el proyecto soporta `ar, de, es, fr, ja, ko, pt-rBR, ru, vi, zh, zh-rTW` — NO `it`, y es `pt-rBR`, no `pt`. Solo hace falta traducir 2 strings nuevas (`settings_tools`, `settings_tools_desc`) en `tools_strings.xml`; el resto se reutiliza sin tocar ningún locale.

---

## 📋 Checklist de Archivos a Modificar/Crear

### Nuevos archivos:
1. `app/src/main/java/com/newoether/agora/ui/settings/SettingsToolsPage.kt`
2. `app/src/main/res/values/tools_strings.xml` (solo `settings_tools` + `settings_tools_desc`; el resto de strings se reutiliza)
3. `app/src/main/res/values-ar/tools_strings.xml`
4. `app/src/main/res/values-de/tools_strings.xml`
5. `app/src/main/res/values-es/tools_strings.xml`
6. `app/src/main/res/values-fr/tools_strings.xml`
7. `app/src/main/res/values-ja/tools_strings.xml`
8. `app/src/main/res/values-ko/tools_strings.xml`
9. `app/src/main/res/values-pt-rBR/tools_strings.xml`
10. `app/src/main/res/values-ru/tools_strings.xml`
11. `app/src/main/res/values-vi/tools_strings.xml`
12. `app/src/main/res/values-zh/tools_strings.xml`
13. `app/src/main/res/values-zh-rTW/tools_strings.xml`

### Archivos existentes a modificar:
14. `app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt` (añadir categoría + navigation case)

**Nota:** `SettingsRepository.kt` NO requiere cambios — verificado que todos los estados necesarios ya existen (ver F1-T1). Tampoco se toca `values/strings.xml` ni sus 11 traducciones — los strings de cada herramienta ya existen y se reutilizan tal cual (ver F1-T2). Locales reales del proyecto (verificado listando `app/src/main/res/`): `ar, de, es, fr, ja, ko, pt-rBR, ru, vi, zh, zh-rTW` — no existe `it`, y es `pt-rBR`, no `pt`.

### Referencias (solo lectura) - Agora:
- `app/src/main/java/com/newoether/agora/ui/settings/SettingsShellPage.kt` (patrón de página)
- `app/src/main/java/com/newoether/agora/ui/settings/SettingsWebSearchPage.kt` (patrón de página)
- `app/src/main/java/com/newoether/agora/ui/settings/SettingsAutomationPage.kt` (SmsSection, NotificationsSection, HeartbeatSection como referencia)
- `app/src/main/java/com/newoether/agora/ui/settings/SettingsSkillsPage.kt` (patrón de página)
- `app/src/main/java/com/newoether/agora/ui/settings/SettingsMemoryPage.kt` (patrón de página)
- `app/src/main/java/com/newoether/agora/ui/settings/SettingsImageGenPage.kt` (patrón de página)
- `app/src/main/java/com/newoether/agora/ui/settings/SettingsSearchPage.kt` (patrón de página)
- `app/src/main/java/com/newoether/agora/ui/settings/SettingsMcpPage.kt` (patrón de página)
- `app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt` (navegación y grupos)
- `app/src/main/java/com/newoether/agora/data/repository/SettingsRepository.kt` (states y setters existentes)
- `app/src/main/java/com/newoether/agora/tool/ShellToolProvider.kt` (tool definitions)
- `app/src/main/java/com/newoether/agora/tool/WebSearchToolProvider.kt` (tool definitions)
- `app/src/main/java/com/newoether/agora/tool/SmsToolProvider.kt` (tool definitions)
- `app/src/main/java/com/newoether/agora/tool/NotificationToolProvider.kt` (tool definitions)
- `app/src/main/java/com/newoether/agora/tool/HeartbeatToolProvider.kt` (tool definitions)
- `app/src/main/java/com/newoether/agora/tool/MemoryToolProvider.kt` (tool definitions)
- `app/src/main/java/com/newoether/agora/tool/ImageGenToolProvider.kt` (tool definitions)
- `app/src/main/java/com/newoether/agora/tool/RagToolProvider.kt` (tool definitions)
- `app/src/main/java/com/newoether/agora/tool/McpToolProvider.kt` (tool definitions)
- `app/src/main/java/com/newoether/agora/tool/AutomationToolProvider.kt` (tool definitions)
- `app/src/main/java/com/newoether/agora/tool/SkillToolProvider.kt` (tool definitions)

### Referencias (solo lectura) - Kai (para estructura de Tools tab):
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/ui/settings/SettingsScreen.kt` (SettingsTab.Tools, navegación, tabs visibles)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/ui/settings/ToolsSettings.kt` (ToolsContent, ToolItem, grid layout, MCP/Skills/Native tools sections)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/network/tools/ToolInfo.kt` (modelo ToolInfo con id, name, description, isEnabled, userToggleable)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/tools/CommonTools.kt` (herramientas: ipLocation, localTime, memoryStore, memoryForget, memoryLearn, memoryReinforce, openUrl)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/tools/WebSearchTool.kt` (WebSearchTool con toolInfo)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/tools/SmsTools.kt` (SMS tools: check_sms, read_sms, search_sms, send_sms, reply_sms)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/tools/NotificationTools.kt` (Notification tools: check_notifications, read_notification, search_notifications)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/tools/SchedulingTools.kt` (Scheduling tools: schedule_task, cancel_task, list_tasks)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/tools/HeartbeatTools.kt` (promote_learning tool)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/tools/EmailTools.kt` (Email tools: setup_email, check_email, read_email, reply_email, compose_email, search_email)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/tools/FetchUrlTool.kt` (fetch_url tool)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/tools/ProcessManagerTool.kt` (Process Manager tool)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/tools/ShellCommandTool.kt` (Shell tool - android/desktop)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/tools/SetAlarmTool.kt` (Set Alarm tool)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/tools/SendNotificationTool.kt` (Send Notification tool)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/tools/OpenFileTool.kt` (Open File tool)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/tools/CreateCalendarEventTool.kt` (Create Calendar Event tool)
- `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/mcp/McpServerManager.kt` (MCP tool definitions dinámicos)
- `composeApp/src/commonMain/composeResources/values/strings.xml` (strings de settings_tab_tools, settings_tools_description, settings_tools_none_available, etc. - 36+ locales)
- `composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/Platform.android.kt` (feature flags SMS, notifications)