# 📱 Plan de Mejora UX: Reorganización y Extensión del Panel de Herramientas (Tools) en Agora
> **Plan ID:** `PLAN-20260912-TOOLS-UX-REFACTOR`
> **Versión:** `1.0.0`
> **Estado:** `Aprobado`

## 🎯 Objetivo y Diagnóstico de UX
Resolver la inconsistencia conceptual en la pantalla de Ajustes de Agora. Actualmente, herramientas orientadas puramente al soporte interno de la IA (como *Web Search*, *Semantic Search*, *MCP*, *Skills*) se mezclaban con capacidades de interacción directa con el dispositivo del usuario (como *Shell*, *SMS*, *Notifications*, *Heartbeat*). 

Siguiendo el modelo inspirado en **Kai**, adoptaremos un **Enfoque Híbrido**:
1. **Reorganización Visual y Semántica:** Dividir la página `SettingsToolsPage` en dos secciones claramente diferenciadas:
   - **AI Backend Tools (Herramientas del Modelo):** Web Search, Semantic/Conversation Search, MCP, Skills, Memory, Image Generation.
   - **Device & Assistant Tools (Asistencia al Dispositivo):** Local/Remote Shell, SMS, Notifications, Heartbeat, Automation, y preparación para futuras herramientas de dispositivo (*Configurar Alarma*, *Abrir Archivo*, *Crear Evento en Calendario*, etc.).
2. **Claridad en Textos y Descripciones:** Actualizar las etiquetas y subtítulos para que el usuario distinga claramente qué habilita capacidades cognitivas para la IA frente a qué otorga permisos de control sobre el sistema operativo móvil.

---

## 🗺️ Fases de Implementación

### Fase 1: Estructuración por Categorías en `SettingsToolsPage.kt`
- [ ] **[F1-T1]** Separar los grupos en `SettingsToolsPage.kt` en dos bloques explícitos:
  - **Grupo 1: "AI & Cognitive Tools (Backend)"** → Web Search, Search (Conversations), MCP, Skills, Memory, Image Generation.
  - **Grupo 2: "Device & Assistant Tools (OS)"** → Shell, Device Tools (SMS / Notifications si está en F-Droid), Heartbeat, Automation Tools.
- [ ] **[F1-T2]** Añadir tarjetas de "Próximas herramientas de asistencia" (desactivadas o informativas) inspiradas en Kai para clarificar la visión de producto (*Configurar Alarma*, *Crear Evento en Calendario*, *Obtener Ubicación*, *Obtener Hora Local*).

### Fase 2: Refinamiento de Textos y Descripciones i18n
- [ ] **[F2-T1]** Actualizar `tools_strings.xml` (y sus 11 copias locales) para incluir los títulos de sección claros y descripciones orientadas a diferenciar capacidades cognitivas vs. automatización del dispositivo.

### Fase 3: Verificación y Compilación
- [ ] **[F3-T1]** Ejecutar pruebas estáticas y compilación limpia:
  - `gradlew.bat verifyKotlinFileSize`
  - `gradlew.bat assembleFdroidDebug`
- [ ] **[F3-T2]** Verificación en dispositivo físico vía ADB.
