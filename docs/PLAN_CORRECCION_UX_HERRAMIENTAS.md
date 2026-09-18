# Plan Técnico: Corrección de Inconsistencias UX en la pantalla "Tools" (Ajustes → Herramientas)

**Estado:** Propuesta para revisión del owner antes de implementar (ver `development/README.md` §Authority Hierarchy y `development/settings-ui-ux.md` §Mandatory design discipline — varias decisiones aquí requieren confirmación explícita antes de codificar).

**Evidencia base:** `tmp/screen.png`, `tmp/screen2.png`, `tmp/screen3.png`, `tmp/screen4.png` (Agora, build actual) y `tmp/Screenshot_20260912_171930_Kai 9000.jpg` (Kai, pestaña "Herramientas", referencia de diseño).

**Archivos fuente inspeccionados:**
- `app/src/main/java/com/newoether/agora/ui/settings/SettingsToolsPage.kt`
- `app/src/main/res/values/tools_strings.xml` + las 11 copias en `values-{ar,de,es,fr,ja,ko,pt-rBR,ru,vi,zh,zh-rTW}/tools_strings.xml`
- `app/src/main/java/com/newoether/agora/tool/*.kt` (implementaciones de `ToolProvider`)
- `app/src/main/AndroidManifest.xml`
- `development/settings-ui-ux.md`, `development/application-ui.md`, `AGENTS.md`
- Referencia cruzada de proyecto: implementación real de las tools de Kai (`SetAlarmTool.kt`, `OpenFileTool.kt`/`SandboxFiles.kt`, `CreateCalendarEventTool.kt`/`CalendarRepository.kt`, `SendNotificationTool.kt`/`NotificationHelper.kt`, `CommonTools.kt`, `FetchUrlTool.kt`) — ver §6.0

---

## 1. Resumen ejecutivo

La pantalla `Tools` (`SettingsToolsPage.kt`) tiene **cuatro problemas de UX independientes**, todos verificables directamente en el código y en las capturas:

| # | Problema | Severidad | Evidencia |
|---|---|---|---|
| A | **Ruptura total de localización**: el archivo `tools_strings.xml` nunca fue traducido en ninguno de los 11 locales soportados. Todas las copias en `values-*/tools_strings.xml` son idénticas byte-a-byte al inglés. | Alta | screen1, screen2, screen3, screen4 muestran inglés y español mezclados en la misma pantalla |
| B | **Violación del contrato de UI de Settings**: dos filas (`Device Tools`, `Upcoming Assistant Tools`) renderizan un `ChevronRight` y tienen un `clickable {}` vacío (no navegan a ningún lado). El contrato `development/settings-ui-ux.md` prohíbe explícitamente el glifo de disclosure pasivo en filas de Settings. | Alta (viola contrato autoritativo) | screen2 (fila "Dev..." cortada por el FAB), screen3 (fila "Device Tools" y "Upcoming Assistant Tools") |
| C | **Conflación de taxonomía**: la categoría "Device & Assistant Tools (OS)" mezcla herramientas de **soporte a la IA** (Shell, SMS, Notificaciones, Heartbeat, Automatización — todas `ToolProvider` que la IA invoca para ejecutar tareas o mantener contexto) con herramientas de **acción directa del asistente hacia el dispositivo** (Configurar alarma, Abrir archivo, etc. — inspiradas en Kai), que conceptualmente son una tercera categoría distinta. | Media-Alta (arquitectónico/conceptual) | screen2, screen3, comparación con Kai |
| D | **Las herramientas inspiradas en Kai no están implementadas**: existen solo como filas estáticas "Coming Soon", sin `ToolProvider`, sin definición de tool-calling, sin permisos declarados, sin lógica. | Alta (funcionalidad faltante, no solo UX) | screen3, screen4 vs. `tool/` (no existe ningún archivo `AlarmToolProvider`, `CalendarToolProvider`, `LocationToolProvider`, etc.) |

Las secciones 2–6 detallan cada hallazgo con su causa raíz exacta, y la sección 7 en adelante da el plan de corrección por fases con archivos concretos a tocar.

---

## 2. Hallazgo A — Ruptura de localización (i18n)

### 2.1 Causa raíz
`app/src/main/res/values/tools_strings.xml` fue añadido como archivo **nuevo** (separado de `strings.xml`) para la reestructuración de la pantalla Tools, pero las 11 copias en `values-ar`, `values-de`, `values-es`, `values-fr`, `values-ja`, `values-ko`, `values-pt-rBR`, `values-ru`, `values-vi`, `values-zh`, `values-zh-rTW` son **copias idénticas del inglés**, no traducciones. Se verificó explícitamente para `es`, `fr`, `de`, `ja` — las cuatro son byte-idénticas al archivo base.

Esto viola directamente dos reglas autoritativas ya existentes en el repo:
- `development/settings-ui-ux.md` → "Copy and localization": *"Every default string key must exist in every supported locale."* (la clave existe, pero el **valor** no fue traducido — mismo efecto práctico: texto en inglés hardcodeado sobre una UI localizada).
- `development/application-ui.md` §3: *"The default resource and every supported locale must define the same key set. App-owned strings are localized in the current Android locale; hard-coded English must not replace resource-backed UI copy."*

### 2.2 Claves afectadas (17 strings en `tools_strings.xml`)
```
settings_tools, settings_tools_desc,
settings_tools_group_ai_backend, settings_tools_group_device_assistant,
settings_device_tools_section, settings_device_tools_desc,
settings_upcoming_tools_section, settings_upcoming_tools_desc,
settings_tool_set_alarm, settings_tool_set_alarm_desc,
settings_tool_open_file, settings_tool_open_file_desc,
settings_tool_create_calendar_event, settings_tool_create_calendar_event_desc,
settings_tool_get_location, settings_tool_get_location_desc,
settings_tool_get_local_time, settings_tool_get_local_time_desc,
settings_tool_coming_soon
```

### 2.3 Corrección
Traducir profesionalmente estas 19 claves en los 11 archivos `values-*/tools_strings.xml`, respetando:
- **Sentence case** para descripciones/estado, **Title Case nativo** solo donde aplique la convención del idioma para títulos de fila/grupo (regla de `application-ui.md`: *"Non-English locales follow their native casing and punctuation conventions."*). En español, esto significa mayúscula inicial únicamente (p. ej. "Generación de imágenes", no "Generación De Imágenes" — confirmar que el resto de `strings.xml` en `values-es` ya sigue este patrón antes de traducir, para no introducir una inconsistencia nueva).
- Reescribir `settings_tools_group_ai_backend` y `settings_tools_group_device_assistant` acorde a la nueva taxonomía de 3 grupos (ver Hallazgo C, sección 4) — no traducir literalmente los nombres viejos si se van a renombrar.
- Añadir un test de paridad de claves (si no existe ya) que falle el build si algún locale no define el mismo `key set` que `values/tools_strings.xml` — esto es exactamente lo que exige `application-ui.md` §3 y evita que este bug se repita con el próximo string nuevo.

---

## 3. Hallazgo B — Chevron/disclosure en filas no navegables (violación de contrato)

### 3.1 Causa raíz
En `SettingsToolsPage.kt`, dos `SettingsItem` usan `Icon(Icons.Default.ChevronRight, ...)` como `trailingContent` y `modifier = Modifier.clickable { /* handled by sub-items */ }`:

```kotlin
// "Device Tools" — línea ~152
SettingsItem(
    headlineContent = { Text(stringResource(R.string.settings_device_tools_section)) },
    ...
    trailingContent = { Icon(Icons.Default.ChevronRight, null, ...) },
    modifier = Modifier.clickable { /* handled by sub-items */ }.fillMaxWidth()
)

// "Upcoming Assistant Tools" — línea ~226
SettingsItem(
    headlineContent = { Text(stringResource(R.string.settings_upcoming_tools_section)) },
    ...
    trailingContent = { Icon(Icons.Default.ChevronRight, null, ...) },
    modifier = Modifier.clickable { /* handled by sub-items */ }.fillMaxWidth()
)
```

Esto es exactamente el patrón que `development/settings-ui-ux.md` prohíbe:

> *"Navigable Settings cards and rows do not render `>`, `KeyboardArrowRight`, `ChevronRight`, or an equivalent passive disclosure glyph. The whole row is the navigation target. Trailing content is reserved for an actual value, status, switch, progress indicator, or explicit action."*

El resultado visual es exactamente el reportado por el usuario: la fila **aparenta** ser navegable (chevron + tarjeta con aspecto de destino), pero al tocarla no ocurre nada (`/* handled by sub-items */` es un comentario, no una implementación) porque en realidad es solo un separador visual y las "sub-filas" ya están listadas debajo, en la misma pantalla, sin jerarquía real. Esto es ambiguo y engañoso para el usuario, y contradice la propia arquitectura del archivo: `MCP` en la misma pantalla usa un patrón correcto (trailing = conteo de servidores como texto, sin chevron) para una fila que sí navega, mientras que estas dos filas usan el chevron para una fila que **no** navega — el patrón está invertido.

### 3.2 Corrección
Reemplazar ambas filas "header-card" por encabezados de sección reales usando el mecanismo ya existente en el codebase para agrupar (`SettingsGroup(title = ...)`), en vez de una fila de contenido con chevron falso. Es decir:
- Eliminar las dos `SettingsItem` de "Device Tools" y "Upcoming Assistant Tools" como filas.
- Usar `SettingsGroup(title = stringResource(...))` para introducir cada subconjunto, igual que ya se hace para "AI & Cognitive Tools (Backend)" / "Device & Assistant Tools (OS)" a nivel superior.
- Si existe una razón de producto real para que "Device Tools" navegue a una subpantalla propia (por ejemplo, para no sobrecargar la pantalla principal de Tools), entonces debe implementarse como navegación real (nueva ruta/página, p. ej. `SettingsDeviceToolsPage.kt` siguiendo el mismo patrón que `SettingsShellPage.kt`), y en ese caso el chevron es válido — pero se debe **decidir una de las dos opciones**, no dejar la apariencia de la primera con el comportamiento de la segunda. Esta decisión de producto (subpantalla real vs. sección inline) debe confirmarla el owner antes de implementar, conforme a la disciplina de diseño obligatoria del contrato.

---

## 4. Hallazgo C — Conflación de taxonomía (AI-support vs. Assistant device-action)

### 4.1 El problema conceptual, en los términos del propio usuario

Cito la aclaración del usuario: las herramientas inspiradas en Kai (`Configurar alarma`, `Abrir archivo`, `Obtener ubicación`, `Obtener hora local`, `Crear evento en calendario`, y por paridad con Kai también `Abrir URL`, `Obtener URL`, `Enviar notificación`) son herramientas que **proyectan una interacción con el móvil para asistencia directa al usuario** — la IA las invoca y el efecto es una acción tangible y visible en el dispositivo, hecha *en nombre del usuario* (suena una alarma, se abre un archivo, se crea un evento).

Esto es distinto de herramientas como leer/enviar SMS o leer notificaciones, que **también** tocan el sistema operativo, pero cuyo propósito es **dar soporte a la IA** para cumplir una solicitud (obtener contexto, ejecutar comandos de shell, correr auto-chequeos) — no son, en sí mismas, "la acción que el usuario pidió", sino infraestructura que hace posible que la IA actúe.

### 4.2 Confirmación en el propio código y arquitectura de Agora

`AGENTS.md` §7.5 ya agrupa así, en la práctica, las implementaciones existentes:

> `ToolProvider` implementations: Memory, RAG, Web Search, Shell, MCP, Image Gen, Tasks/Loops, **SMS, Notifications, Heartbeat**

Es decir: Shell, SMS, Notifications y Heartbeat ya conviven arquitectónicamente como "tools de soporte a la IA" junto con Memory/RAG/Web Search/MCP — todas exponen `ToolDefinition`s que el modelo decide invocar para **cumplir una tarea o mantener funcionamiento autónomo**, no para "hacer una cosa puntual y visible por el usuario" al estilo Kai.

La captura de Kai (`Screenshot_20260912_171930_Kai 9000.jpg`) confirma el patrón de referencia: **una sola lista plana** bajo la pestaña "Herramientas", con la descripción general *"Habilita o deshabilita herramientas de IA que pueden realizar acciones en tu nombre"* — Kai no separa por "backend" vs "OS"; separa implícitamente por *pestaña* (`General`, `Agente`, `Servicios`, `Herramientas`, `Linux Sandbox`), y dentro de "Herramientas" solo viven las acciones de tipo "hacer algo visible en el dispositivo en nombre del usuario" (búsqueda web incluida, porque devuelve un resultado que informa la respuesta, pero el resto — hora local, ubicación, abrir URL, obtener URL, enviar notificación, crear evento, configurar alarma, abrir archivo — son *todas* acciones puntuales de una sola llamada, sin estado persistente ni de vigilancia).

### 4.3 Taxonomía corregida propuesta (3 categorías, no 2)

| Categoría | Criterio | Miembros actuales | Archivo actual |
|---|---|---|---|
| **1. Herramientas de IA y Contenido** (`settings_tools_group_ai_content`) | Dan soporte de conocimiento/razonamiento al modelo; no generan una acción visible única en el dispositivo. | Búsqueda web, Búsqueda de conversaciones, MCP, Habilidades, Memoria, Generación de imágenes | Ya existen, sin cambios funcionales |
| **2. Herramientas de soporte a la IA (nivel SO)** (`settings_tools_group_ai_os_support`) | Tocan el sistema operativo pero su función es alimentar/ejecutar tareas de la IA de forma continua o instrumental, no una acción puntual pedida por el usuario. | Shell, SMS, Notificaciones, Heartbeat, Automatización | Mover aquí desde el grupo actual "Device & Assistant Tools (OS)" |
| **3. Herramientas de Asistente (acción en el dispositivo)** (`settings_tools_group_assistant_actions`) | Una sola invocación produce una acción tangible y visible en el dispositivo, en nombre del usuario. Inspiradas en Kai. | Configurar alarma, Abrir archivo, Crear evento de calendario, Obtener ubicación, Obtener hora local (+ opcional: Abrir URL, Obtener URL, Enviar notificación — ver §6.6) | **Nuevo grupo real**, reemplaza el actual subgrupo placeholder |

**Nota importante:** `Shell` actualmente vive **fuera** del bloque condicional `if (smsReaderSupported || notificationListenerSupported)` (línea ~139 de `SettingsToolsPage.kt`), es decir, se muestra siempre, incluso en la flavor `play` donde SMS/Notificaciones no existen. Al mover Shell al grupo 2 junto con SMS/Notificaciones/Heartbeat/Automatización, verificar que la condición de visibilidad de cada fila se mantenga igual que hoy (Shell siempre visible en ambos flavors; SMS/Notificaciones solo si `smsReaderSupported`/`notificationListenerSupported`; Heartbeat/Automatización siempre visibles) — **no cambiar disponibilidad por flavor, solo agrupación visual**.

### 4.4 Renombrado de labels
Los nombres de grupo actuales están en inglés técnico interno ("Backend", "(OS)") que no comunican nada al usuario final y encima nunca se tradujeron (Hallazgo A). Se recomienda:
- ~~`AI & Cognitive Tools (Backend)`~~ → **`Herramientas de IA`** / *"AI Tools"* (grupo 1)
- ~~`Device & Assistant Tools (OS)`~~ → **`Herramientas del sistema`** / *"System Tools"* (grupo 2, contiene Shell/SMS/Notificaciones/Heartbeat/Automatización)
- Nuevo → **`Herramientas del asistente`** / *"Assistant Tools"* (grupo 3, las inspiradas en Kai)

Esta redacción exacta debe confirmarla el owner (es una decisión de copy de producto, no solo técnica), pero el punto no-negociable es: **no usar paréntesis con jerga interna ("Backend", "(OS)") en copy visible al usuario**, y **no mezclar los tres significados bajo dos etiquetas**.

---

## 5. Hallazgo D — Las herramientas Kai no están implementadas (son solo "Coming Soon")

### 5.1 Estado actual
En `SettingsToolsPage.kt`, las 5 filas (`settings_tool_set_alarm`, `settings_tool_open_file`, `settings_tool_create_calendar_event`, `settings_tool_get_location`, `settings_tool_get_local_time`) son `SettingsItem` puramente decorativos:
- Icono atenuado al 50% de alpha (`onSurfaceVariant.copy(alpha = 0.5f)`), sin `Switch`.
- `trailingContent` es un `Text("Coming Soon")`, no un control.
- Sin `modifier.clickable`, sin estado en `SettingsRepository`/DataStore, sin `ToolProvider` correspondiente en `app/src/main/java/com/newoether/agora/tool/`.

Es decir, **no existen como funcionalidad** — no hay entrada en `SettingsPreferenceSchema.kt` (equivalente a `SHELL_ENABLED`, `NOTIFICATIONS_ENABLED`, etc.), no hay `ToolDefinition`, no hay permisos en `AndroidManifest.xml`, y ningún archivo bajo `tool/` los implementa.

### 5.2 Por qué esto también es un problema de UX y no solo de alcance
Mostrar un toggle deshabilitado con "Coming Soon" en producción, dentro de una pantalla que por lo demás es 100% funcional, es información engañosa: el usuario no puede distinguir "esto está construido pero apagado por configuración" de "esto no existe todavía". Combinado con el Hallazgo B (falsos chevrons) y el Hallazgo C (categoría mal etiquetada como "Assistant Tools" cuando en realidad son "próximamente"), el efecto acumulado es que la sección completa se percibe como rota o a medio hacer, incluso donde el resto de la app (Shell, MCP, Memoria, etc.) está completamente terminado y pulido.

**Recomendación:** o se implementan realmente (plan en la sección 6), o se retiran de la build de producción hasta que existan (feature-flag / build config), pero no deben quedar como decoración permanente indistinguible de una función real deshabilitada.

---

## 6. Plan de implementación real de las herramientas tipo Kai

Esta sección es un diseño técnico propuesto; **requiere confirmación del owner antes de codificar**, conforme a `development/README.md` (jerarquía de autoridad) y `development/settings-ui-ux.md` (disciplina de diseño obligatoria) — en particular las decisiones marcadas "🔶 Decisión de producto pendiente".

### 6.0 Referencia de implementación real de Kai (autoritativa para esta sección)

Kai ya tiene estas herramientas construidas y en producción. Su código fuente es la referencia más directa disponible (más que inferir desde la captura de pantalla) y reemplaza las suposiciones hechas en la versión anterior de este plan donde había conflicto. Resumen por archivo:

| Tool | Archivo en Kai | Mecanismo real |
|---|---|---|
| `set_alarm` | `androidMain/.../tools/SetAlarmTool.kt` | `Intent(AlarmClock.ACTION_SET_ALARM)` / `ACTION_SET_TIMER` con `EXTRA_SKIP_UI=true` (silencioso, sin confirmación visual). **Sin permisos.** Params: `hour`, `minutes`, `label`, `duration_seconds` (alarma XOR timer) |
| `open_file` | `androidMain/.../tools/OpenFileTool.kt` + `SandboxFiles.kt` | Abre **solo archivos del sandbox** (`/root`), nunca del sistema de archivos general: `resolveSandboxFile(homePath, rel)` rechaza paths absolutos y `..`; `openFileWithIntent` usa FileProvider + adivinación de MIME (overrides para `apk`, `ts`, etc.) |
| `create_calendar_event` | `androidMain/.../tools/CreateCalendarEventTool.kt` + `CalendarRepository.kt` | **Inserción directa vía `CalendarContract`**, no intent implícito — requiere runtime permission `READ_CALENDAR`/`WRITE_CALENDAR` vía `PermissionController`. Parseo ISO-8601 robusto (`OffsetDateTime` → `Instant` → naive en zona local). Reminder default 15 min, fin default +1h |
| `send_notification` | `androidMain/.../tools/SendNotificationTool.kt` + `NotificationHelper.kt` | Canal propio `kai_ai_notifications`, `NotificationCompat` con `PendingIntent` al launcher, runtime `POST_NOTIFICATIONS` |
| `get_local_time` | `commonMain/.../tools/CommonTools.kt` | `kotlinx.datetime`; devuelve `iso_datetime`, `display_datetime`, `timezone`, `day_of_week`. Sin parámetros ni permisos. La descripción del schema instruye al modelo a llamarlo primero ante fechas relativas |
| `get_location_from_ip` | `CommonTools.kt` | **Basada en IP, cero permisos** — GET a `https://ipwho.is/` (ktor, timeout 10 s) → ciudad/región/país/lat/lon/timezone/ISP |
| `open_url` | `CommonTools.kt` | `Intent.ACTION_VIEW` (expect/actual). El propio schema advierte explícitamente al modelo: *"NO recibirás el contenido; no usar para leer páginas"* |
| `fetch_url` | `commonMain/.../tools/FetchUrlTool.kt` | GET/POST/HEAD, **bloquea hosts privados/loopback** (127.x, 10.x, 192.168.x, 172.16–31, 169.254, localhost, IPv6 local — mitigación SSRF), strip de HTML, truncado, User-Agent propio, soporta RFC 8058 (one-click unsubscribe) |

**Patrón de registro/toggle en Kai:** cada tool declara `ToolInfo(userToggleable: Boolean)`; la pestaña Herramientas solo muestra el switch si `userToggleable = true`, y `getCommonTools(appSettings)` filtra en runtime por `AppSettings.isToolEnabled(name)`. Esto es equivalente 1:1 a lo que este plan ya proponía en §6.2 (`booleanPreferencesKey` + `Switch`), así que no cambia el diseño de persistencia de Agora, solo lo confirma.

Kai también mantiene un `LOCAL_TOOL_ALLOWLIST` — el modelo on-device (llama.cpp local) solo recibe un subconjunto reducido de tools simples: `get_local_time`, `get_location_from_ip`, `web_search`, `open_url`, `execute_shell_command`, memoria parcial. **Relevante para Agora**, que también soporta modelos locales GGUF vía llama.cpp (`AGENTS.md` §1): si se implementan estas herramientas, decidir si el modelo local recibe las 5-8 completas o un subconjunto equivalente, dado que modelos locales suelen tener peor fidelidad de function-calling con esquemas complejos (p. ej. `create_calendar_event` con parseo ISO-8601 y permisos runtime es mal candidato para exponer a un modelo local; `get_local_time`/`get_location_from_ip`/`open_url` sí son buenos candidatos, igual que en Kai). 🔶 Decisión de producto pendiente — no bloquea el resto del plan, pero debe resolverse antes de wiring en `AppContainer` si Agora expone tools distintas por tipo de provider (remoto vs. local).

### 6.1 Patrón arquitectónico a seguir
Igual que `ShellToolProvider`, `NotificationToolProvider`, `SmsToolProvider`, `HeartbeatToolProvider` — implementar `ToolProvider` (`tool/ToolProvider.kt`) por cada acción o, preferiblemente, **un solo archivo** `AssistantDeviceToolProvider.kt` que agrupe las 5 (u 8) tool-definitions de "acción puntual sobre el dispositivo", porque:
- Comparten el mismo invariante real: son ejecuciones de un solo intent/llamada de sistema, sin streaming, sin estado persistente propio, resultado inmediato (`ToolExecutionResult` simple, sin `Flow` de progreso).
- `AGENTS.md` §4.5 exige no introducir una nueva abstracción/archivo por herramienta si un owner + parámetro ya la resuelve seguramente; un único `ToolProvider` con 5-8 `when(name)` es consistente con cómo `ShellToolProvider`/`ShellToolDefinitions.kt` ya agrupan varias tool-definitions relacionadas.
- Si el archivo se acerca al límite de 999 líneas (`kotlin-source-size-policy.md`), separar en `AssistantDeviceToolDefinitions.kt` (definiciones) + `AssistantDeviceToolProvider.kt` (ejecución), replicando el patrón `ShellToolDefinitions.kt` / `ShellToolProvider.kt`.

### 6.2 Nuevas claves de DataStore (`SettingsPreferenceSchema.kt`)
Siguiendo el patrón `booleanPreferencesKey` ya usado para `SHELL_ENABLED`/`NOTIFICATIONS_ENABLED`:
```kotlin
internal val ASSISTANT_SET_ALARM_ENABLED = booleanPreferencesKey("assistant_set_alarm_enabled")
internal val ASSISTANT_OPEN_FILE_ENABLED = booleanPreferencesKey("assistant_open_file_enabled")
internal val ASSISTANT_CREATE_CALENDAR_EVENT_ENABLED = booleanPreferencesKey("assistant_create_calendar_event_enabled")
internal val ASSISTANT_GET_LOCATION_ENABLED = booleanPreferencesKey("assistant_get_location_enabled")
internal val ASSISTANT_GET_LOCAL_TIME_ENABLED = booleanPreferencesKey("assistant_get_local_time_enabled")
```
Exponer los correspondientes `Flow<Boolean>` y `setXxxEnabled()` en `SettingsManager.kt`/`SettingsRepository`, igual que `shellEnabled`/`setShellEnabled`.

### 6.3 Permisos Android requeridos — decisiones actualizadas con la implementación real de Kai
Estado actual en `AndroidManifest.xml`: ya existe `SCHEDULE_EXACT_ALARM` (usado hoy por `AutomationAlarmReceiver`). No existen `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `READ_CALENDAR` ni `WRITE_CALENDAR`.

| Herramienta | Enfoque | Permiso Android | Estado de la decisión |
|---|---|---|---|
| **Configurar alarma** | `Intent(AlarmClock.ACTION_SET_ALARM)` / `ACTION_SET_TIMER`, delega en la app de reloj del usuario | Ninguno (intent implícito estándar) | Confirmado por Kai. Nuevo punto pendiente: Kai usa `EXTRA_SKIP_UI=true` (crea la alarma en silencio, sin abrir la app de Reloj). Decidir si Agora replica ese comportamiento silencioso o fuerza `EXTRA_SKIP_UI=false` para mantener visibilidad/control del usuario (recomendado, dado el enfoque BYOK de Agora). |
| **Crear evento de calendario** | Conflicto sin resolver entre el plan original y Kai: el plan proponía `Intent.ACTION_INSERT` (delega, sin permisos); **Kai en cambio inserta directo vía `CalendarContract`** (`CreateCalendarEventTool.kt` + `CalendarRepository.kt`) | `ACTION_INSERT`: ninguno. Estilo Kai: `READ_CALENDAR` + `WRITE_CALENDAR` | **No resuelto — decisión de owner pendiente.** Kai da un resultado verificable (`event_id`) que el modelo puede reportar como confirmación real, pero suma dos permisos sensibles nuevos. `ACTION_INSERT` es más liviano pero el tool-call no puede confirmar programáticamente que el usuario guardó el evento (resultado optimista, no verificado). Sería el primer permiso runtime peligroso de Agora si se adopta el enfoque Kai. |
| **Obtener hora local** | `TimeZone.getDefault()` + `Locale` actual (Kai usa `kotlinx.datetime`; Agora puede usar `java.time` nativo sin nueva dependencia) | Ninguno | Confirmado por Kai, sin permisos ni parámetros |
| **Obtener ubicación** | Confirmado por Kai: geolocalización por IP, `GET https://ipwho.is/` (timeout 10 s) → ciudad/región/país/lat/lon/timezone/ISP | Ninguno | Confirmado — coincide exactamente con la recomendación original de este plan. Ajustar `settings_tool_get_location_desc` para decir "ubicación aproximada vía IP", no "GPS", y evaluar `ipwho.is` u otro proveedor equivalente sin costo. |
| **Abrir archivo** | Confirmado por Kai: **solo archivos del sandbox**, nunca el sistema de archivos general — `resolveSandboxFile(homePath, rel)` rechaza rutas absolutas y `..`, luego `openFileWithIntent` usa FileProvider + adivinación de MIME con overrides explícitos | Ninguno (FileProvider ya configurado) | Confirmado — coincide con la opción (a) que este plan ya recomendaba. Agora ya tiene `FileProvider`/`SandboxDocumentsProvider`; el rechazo de `..`/rutas absolutas debe copiarse tal cual por ser mitigación de path traversal, no solo preferencia de diseño. |

### 6.4 Nuevo contrato de módulo
Dado que esta es una categoría de herramientas nueva con invariantes propios (permisos, delegación a apps externas vía intents, ausencia de streaming, comportamiento "una sola acción, un solo resultado"), y siguiendo el patrón de `AGENTS.md` §5 (Module Contract Registry), crear:

`development/assistant-device-tools.md` — contrato que documente:
- Qué herramientas pertenecen a esta categoría y su criterio de pertenencia (sección 4.3 de este plan).
- Que ninguna de estas herramientas solicita permisos runtime peligrosos si existe un intent implícito equivalente (alarma, calendario) — solo permisos cuando es estrictamente necesario (ubicación, si se decide GPS en vez de IP).
- Formato de resultado esperado (`ToolExecutionResult` no-streaming, un solo `Completed`).
- Comportamiento cuando la app de destino (reloj, calendario) no existe en el dispositivo (fallback / error legible para el modelo).

Y añadir la fila correspondiente a la tabla de `AGENTS.md` §5.

### 6.5 UI de la fila real (reemplazo del placeholder)
Cada fila pasa de `Text("Coming Soon")` + icono atenuado a un `Switch` real conectado al DataStore, exactamente como las demás filas de la pantalla (mismo componente `SettingsItem`, mismo patrón `trailingContent = { Switch(...) }` + `modifier.clickable { ... }`). Sin nuevos componentes de UI — 100% reutilización de `SettingsItem`/`SettingsGroup` ya existentes, conforme al baseline de `settings-ui-ux.md`.

### 6.6 Paridad completa con Kai (fuera del alcance mínimo, para consideración del owner)
Kai también expone `open_url`, `fetch_url` y `send_notification`, que el placeholder actual de Agora no contempla en absoluto. Si el objetivo es paridad funcional con Kai (como sugiere el mensaje del usuario: "entre otras"), estas tres deberían añadirse al mismo grupo 3 y al mismo `AssistantDeviceToolProvider`:

- **Abrir URL** (`open_url`) → `Intent(Intent.ACTION_VIEW, Uri.parse(url))`, sin permisos. Copiar también la advertencia explícita que Kai pone en el schema de la tool ("NO recibirás el contenido; no usar para leer páginas") para evitar que el modelo confunda esto con un fetch.
- **Obtener URL** (`fetch_url`) → **ya existe una función equivalente en Agora**: `tool_web_fetch` (`Fetching %1$s…`, `Fetched %1$s`, strings ya en `strings.xml`). No construir una tool duplicada; en su lugar, **endurecer la implementación existente** con las protecciones que sí tiene la de Kai (`FetchUrlTool.kt`) y que Agora aparentemente no tiene hoy:
  - Bloqueo de hosts privados/loopback (SSRF): `127.x`, `10.x`, `192.168.x`, `172.16–31`, `169.254.x`, `localhost`, equivalentes IPv6 locales. Esto es una mitigación de seguridad real (evita que el modelo use el fetch para escanear la red local del dispositivo o alcanzar servicios internos) y debe tratarse como corrección de seguridad independiente del resto de este plan de UX, no como "nice to have".
  - Soporte de método (`GET`/`POST`/`HEAD`), strip de tags HTML antes de devolver el cuerpo al modelo, truncado de longitud, y User-Agent propio de Agora en vez del default de la librería HTTP.
  - Evaluar si vale la pena el soporte RFC 8058 (one-click unsubscribe) de Kai; es un caso de uso específico de bandeja de correo que puede no aplicar al alcance actual de Agora.
- **Enviar notificación** (`send_notification`) → `NotificationManagerCompat` con un canal propio para notificaciones iniciadas por la IA (análogo al canal `kai_ai_notifications` de Kai; no reutilizar el canal del foreground service, que tiene un propósito y ciclo de vida distintos), `PendingIntent` al launcher, permiso `POST_NOTIFICATIONS` ya declarado en el manifest.

🔶 Esta expansión de alcance debe confirmarla el owner explícitamente; no forma parte del arreglo mínimo de UX pero se documenta aquí para que la decisión de alcance sea explícita y no se repita el patrón de placeholders incompletos. El endurecimiento SSRF de `tool_web_fetch`, en cambio, es independiente de esta decisión de alcance y se recomienda evaluarlo aparte cuanto antes por ser una brecha de seguridad potencial, no solo una mejora de paridad.

---

## 7. Archivos a modificar (lista consolidada)

| Archivo | Cambio |
|---|---|
| `app/src/main/res/values/tools_strings.xml` | Renombrar claves de grupo (nueva taxonomía de 3 categorías), añadir claves para `Abrir URL`/`Obtener URL`/`Enviar notificación` si se aprueba §6.6 |
| `app/src/main/res/values-{ar,de,es,fr,ja,ko,pt-rBR,ru,vi,zh,zh-rTW}/tools_strings.xml` | Traducir realmente las 19 claves (actualmente copias del inglés) |
| `app/src/main/java/com/newoether/agora/ui/settings/SettingsToolsPage.kt` | Eliminar `ChevronRight` + `clickable{}` vacío en "Device Tools"/"Upcoming Assistant Tools"; reestructurar en 3 `SettingsGroup`; mover Shell/SMS/Notificaciones/Heartbeat/Automatización al grupo 2; reemplazar los 5 placeholders "Coming Soon" por `SettingsItem` reales con `Switch` |
| `app/src/main/java/com/newoether/agora/data/SettingsPreferenceSchema.kt` | Añadir 5 nuevas `booleanPreferencesKey` |
| `app/src/main/java/com/newoether/agora/data/SettingsManager.kt` (o repositorio equivalente) | Exponer `Flow<Boolean>` + setters para las 5 nuevas keys |
| `app/src/main/java/com/newoether/agora/tool/AssistantDeviceToolDefinitions.kt` (nuevo) | Definiciones de tool-calling para las 5 (u 8) herramientas |
| `app/src/main/java/com/newoether/agora/tool/AssistantDeviceToolProvider.kt` (nuevo) | Implementación de `ToolProvider`, ejecución vía Android `Intent`s |
| `app/src/main/java/com/newoether/agora/di/AppContainer.kt` | Registrar el nuevo `ToolProvider` en la composición de providers, igual que los demás |
| `app/src/main/AndroidManifest.xml` | Ningún permiso nuevo si se siguen las recomendaciones de intents implícitos de §6.3 (confirmar tras decisión del owner sobre ubicación) |
| `development/assistant-device-tools.md` (nuevo) | Contrato de módulo para la nueva categoría |
| `AGENTS.md` §5 | Añadir fila a la tabla de Module Contract Registry apuntando al nuevo contrato |

---

## 8. Fases de ejecución sugeridas

1. **Fase 0 — Localización (bajo riesgo, alto impacto inmediato):** traducir las 11 copias de `tools_strings.xml`. Corrige visualmente el 80% de lo mostrado en las capturas sin tocar lógica ni layout.
2. **Fase 1 — Cumplimiento de contrato de UI:** quitar los `ChevronRight` falsos (Hallazgo B), decidir subpantalla real vs. sección inline.
3. **Fase 2 — Retaxonomía:** mover Shell al grupo de soporte-SO junto a SMS/Notificaciones/Heartbeat/Automatización; crear el tercer grupo real "Herramientas del asistente"; renombrar labels de grupo (requiere aprobación de copy).
4. **Fase 3 — Implementación funcional de las 5 herramientas Kai** (o retiro de la UI de producción si no se va a implementar en este ciclo): DataStore keys, `ToolProvider`, permisos/intents, contrato de módulo nuevo, wiring en `AppContainer`.
5. **Fase 4 (opcional, requiere aprobación de alcance) — Paridad total con Kai:** Abrir URL / Obtener URL / Enviar notificación.

Cada fase debe cerrar con el **Development Completion Gate** de `AGENTS.md` §9: releer contratos aplicables, revisar el diff contra los 15 invariantes arquitectónicos, correr pruebas focalizadas, y correr el gate completo de build (`gradlew.bat -p build-logic test :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest verifyKotlinFileSize --no-daemon --max-workers=1 --stacktrace`).

---

## 9. Criterios de aceptación

- [ ] Ningún string de `tools_strings.xml` se muestra en inglés cuando el idioma del sistema/app es distinto de inglés, en ningún locale soportado.
- [ ] Ninguna fila de la pantalla Tools renderiza `ChevronRight`/`>` salvo que efectivamente navegue a otra pantalla al tocarla completa.
- [ ] Shell aparece agrupado junto a SMS/Notificaciones/Heartbeat/Automatización bajo una etiqueta que no confunde "soporte a la IA" con "acción del asistente".
- [ ] Las 5 herramientas inspiradas en Kai, si se implementan en este ciclo, tienen: toggle funcional persistido, `ToolProvider` real, y ejecutan una acción verificable en el dispositivo (alarma/calendario/hora/ubicación/archivo). Si no se implementan en este ciclo, no aparecen en la build de producción como filas "Coming Soon" indefinidas.
- [ ] Existe un contrato de módulo (`development/assistant-device-tools.md`) referenciado desde `AGENTS.md` §5 si se implementa la Fase 3.
- [ ] La decisión de calendario (`ACTION_INSERT` sin permisos vs. inserción directa estilo Kai con `READ_CALENDAR`/`WRITE_CALENDAR`) quedó resuelta explícitamente por el owner antes de escribir `CreateCalendarEventTool`/equivalente, no asumida por defecto.
- [ ] Si se implementa la Fase 4 (paridad Kai), `tool_web_fetch` quedó endurecido contra SSRF (bloqueo de hosts privados/loopback) independientemente de si `fetch_url`/`open_url`/`send_notification` se construyen en este ciclo.
- [ ] `verifyKotlinFileSize` y el resto del gate de CI pasan sin excepciones nuevas.

---

## 10. Notas finales

Este documento es un **plan**, no una implementación. Las secciones marcadas 🔶 son decisiones de producto/diseño que, conforme a la disciplina obligatoria de `development/settings-ui-ux.md` ("stop and obtain owner confirmation before implementation... do not fill the gap with an autogenerated default"), deben confirmarse explícitamente antes de escribir código. El resto (Hallazgos A y B, y la reagrupación de la Fase 2) son correcciones directas de bugs verificados contra contratos ya autoritativos en el repositorio y pueden implementarse sin ambigüedad adicional.
