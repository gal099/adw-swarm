# Reporte de Arquitectura — SwarmForge

> Generado el: 2026-08-11

---

## 1. Resumen del Enjambre

**SwarmForge** es una plataforma de orquestación de agentes IA basada en `tmux` que convierte múltiples instancias de agentes AI (Claude, Codex, Copilot, Grok) en un equipo de desarrollo disciplinado. Su filosofía central es:

- **Isolación por worktree**: cada agente trabaja en su propia rama de git (`.worktrees/<role>`), evitando colisiones de cambios.
- **Comunicación asincrónica por archivos**: no hay sockets ni RPCs directos entre agentes; todo se comunica mediante archivos `.handoff` en directorios de inbox/outbox.
- **Configuración declarativa**: la topología del enjambre (roles, backends, worktrees) se define en un único archivo `swarmforge/swarmforge.conf`.
- **Constitución estratificada**: las reglas de comportamiento de cada agente se construyen por capas (artículos compartidos en `main` + artículos locales por branch de workflow).

Hay tres workflows predefinidos, elegidos según el tamaño del proyecto:
| Workflow | Agentes | Uso |
|----------|---------|-----|
| `two-pack` | `coder` → `cleaner` | Tareas pequeñas, loop rápido sin QA |
| `four-pack` | `specifier` → `coder` → `refactorer` → `architect` | Proyectos medianos con Gherkin |
| `six-pack` | `specifier` → `coder` → `cleaner` → `architect` → `hardender` → `QA` | Proyectos grandes con QA completo |

---

## 2. Mecanismo de Orquestación `tmux`

### Socket aislado por proyecto
- SwarmForge NO usa el servidor tmux del sistema; crea un **socket exclusivo** guardado en `.swarmforge/tmux-socket`.
- Cada proyecto tiene su propio universo tmux, completamente aislado de otras sesiones del usuario.

### Creación de sesiones
El script principal (`swarmforge.bb`, invocado por `swarmforge.sh` vía Babashka) realiza este flujo:
1. Lee `swarmforge/swarmforge.conf` línea a línea (`window <role> <agent> <worktree> [task|batch] [extra-args...]`).
2. Crea **una sesión tmux por rol** con nombre único (registrado en `.swarmforge/sessions.tsv`).
3. Abre una ventana de terminal (via `swarm-terminal-adapter.sh`) conectada a cada sesión.

### Adaptadores de terminal
El sistema detecta y soporta múltiples backends de terminal:
- `terminal-app.sh` — macOS Terminal.app (default en Mac, usa AppleScript)
- `ghostty.sh` — Ghostty (tabs)
- `iterm2.sh` — iTerm2
- `windows-terminal.sh` — Windows Terminal (WSL)
- `none.sh` — sin automatización; adjunta la sesión de cleanup en el shell actual

Override manual: `SWARMFORGE_TERMINAL=ghostty ./swarm`

### Watchdog de ventanas
`swarm-window-watchdog.bb/.sh` monitorea las ventanas abiertas. Si una ventana se cierra accidentalmente (excepto la primera/"cleanup window"), la reabre y la re-adjunta a la sesión tmux, preservando el estado del agente.

### Destrucción del enjambre
Dos caminos:
1. **Cierre de la primera ventana** (cleanup window) — es el shutdown intencional.
2. **Script `close-swarm [project-root]`** — mata sesiones, detiene el daemon, cierra ventanas rastreadas.

`swarm-cleanup.sh` ejecuta en orden: (1) stop `handoffd`, (2) `tmux kill-session` para cada sesión, (3) cierre de IDs de ventanas de terminal rastreados en `.swarmforge/window-ids`.

---

## 3. Flujo de Comunicación Inter-Agente

### Arquitectura: Daemon + Archivos (NO sockets directos)

```
Agente A (worktree-a)          handoffd.bb              Agente B (worktree-b)
      |                            |                          |
  swarm_handoff.sh             polling outbox/            ready_for_next.sh
      |                            |                          |
  outbox/<file>.handoff  ──────►  copia a ──────────►  inbox/new/<file>.handoff
                                  inbox/new/           +  tmux send-keys (wake-up)
                                  mueve a sent/
```

### Estructura de directorios de estado (por worktree)
```
.swarmforge/handoffs/
  outbox/
    tmp/          ← escritura atómica temporal
    *.handoff     ← archivos listos para que el daemon procese
  sent/           ← audit trail de enviados
  failed/         ← enviados fallidos con diagnósticos
  inbox/
    new/          ← recibidos, pendientes
    in_process/   ← actualmente siendo procesado por el agente
    completed/    ← audit trail de completados
```

### Proceso de envío (swarm_handoff.sh → swarm_handoff.bb)
1. Agente escribe un draft con headers estructurados (sin body).
2. `swarm_handoff.sh` valida campos, canonicaliza el commit SHA (exactamente 10 hex chars), genera `id`, `created_at`, nombre de archivo con formato `<priority>_<timestamp>_<seq>_from_<sender>_to_<recipients>.handoff`.
3. Escribe a `outbox/tmp/<file>.tmp`, luego rename atómico a `outbox/<file>.handoff`.
4. `handoffd.bb` detecta el archivo (polling cada 1 segundo), lo copia a cada inbox de destinatario añadiendo headers `recipient` y `enqueued_at`, envía wake-up tmux genérico, mueve original a `sent/`.

### Wake-up tmux (intencionalmenre genérico)
```
"You have new handoff mail. If idle, run ready_for_next.sh."
```
No nombra el archivo específico para forzar procesamiento en orden de cola (por prioridad + timestamp).

### Tipos de mensaje
| Tipo | Uso | Campo clave |
|------|-----|-------------|
| `git_handoff` | Entregar trabajo comiteado | `commit: <10-hex-chars>` |
| `note` | Mensaje corto de texto libre | `message: <≤80 chars>` |

### Ciclo de vida del agente receptor
```
notificado
  → ready_for_next.sh
    → NO_TASK: detener
    → TASK: <path>: ejecutar PAYLOAD
    → BATCH: <path>: ejecutar cada BATCH_ITEM
  [trabajar...]
  → done_with_current.sh
    → chequea siguiente task/batch automáticamente
```

### Modos de recepción
- **`task`** (default): procesa un handoff a la vez; `ready_for_next_task.sh` / `done_with_current_task.sh`.
- **`batch`**: agrupa todos los handoffs de igual prioridad como unidad; `ready_for_next_batch.sh` / `done_with_current_batch.sh`.

---

## 4. Especialización de Roles

### Definición de roles
Cada rol se define mediante un archivo `swarmforge/roles/<role>.prompt` en el branch corriente. No existe un set fijo de roles en el código; la topología es completamente declarativa.

### Constitución (reglas base por capas)
```
swarmforge/constitution.prompt   ← entry point; indica leer artículos
swarmforge/constitution/articles/
  engineering.prompt             ← instalación de herramientas CRAP/mutation/DRY,
                                    TDD, aceptación con Gherkin
  handoffs.prompt                ← protocolo de envío/recepción de handoffs
  workflow.prompt                ← disciplina de worktrees, commit messages,
                                    bylines de rol, archivos temporales
  [local-engineering.prompt]     ← excepciones/adiciones por branch (opcional)
  [local-workflow.prompt]        ← excepciones/adiciones por branch (opcional)
  [project.prompt]               ← topología y shape del workflow específico
```

Los artículos `local-*.prompt` **añaden** a los compartidos. Para reemplazar completamente un artículo compartido, el branch debe commitar un archivo con el mismo nombre (e.g., `workflow.prompt`).

### Roles por workflow
**two-pack:**
- `coder`: TDD + unit tests + accept tests
- `cleaner`: cleanup, CRAP/DRY review, refactoring, mutation hardening

**four-pack:**
- `specifier`: Gherkin specs + aprobación
- `coder`: implementa + genera accept tests
- `refactorer`: cleanup + cobertura + CRAP/DRY
- `architect`: estructura, dependencias, mutation hardening, notificación de completion

**six-pack:**
- `specifier`: Gherkin specs + procedimientos QA
- `coder`: implementa + unit/accept tests
- `cleaner`: cleanup local + cobertura + mutation scan
- `architect`: módulos, boundaries, property tests
- `hardender`: mutation hardening + Gherkin mutation
- `QA`: scripts ejecutables, verificación UI final, notificación de completion

### Chain forwarding obligatorio
Cada rol intermedio **SIEMPRE** debe hacer forward al siguiente rol, aunque solo haya cambios de formato o metadata. Solo el handoff terminal (broadcast) es merge-only sin reforward.

---

## 5. Requisitos del Sistema

### Dependencias de runtime obligatorias
| Herramienta | Uso |
|-------------|-----|
| `zsh` | Shell del sistema (todos los scripts) |
| `git` | Worktrees, commits, validación de SHAs |
| `tmux` | Sesiones y wake-up notifications |
| `bb` (Babashka) | Daemon (`handoffd.bb`), scripts principales |
| Al menos un backend AI | `claude`, `codex`, `copilot`, o `grok` |

### Herramientas opcionales/condicionales
| Herramienta | Condición |
|-------------|-----------|
| `caffeinate` | macOS — prevenir sleep mientras el enjambre está activo |
| `systemd-inhibit` | Linux — idem |
| `osascript` (AppleScript) | macOS — abrir ventanas Terminal.app |
| `wt.exe` | Windows — abrir Windows Terminal |

### Variables de entorno reconocidas
| Variable | Efecto |
|----------|--------|
| `SWARMFORGE_TERMINAL` | Override del backend de terminal (`ghostty`, `terminal-app`, `windows-terminal`, `none`) |
| `SWARMFORGE_PREVENT_SLEEP=0` | Deshabilitar inhibidor de sleep |
| `SWARMFORGE_ROLE` | Inyectado por el launcher; los helpers lo leen para identificar el rol activo |
| `SWARMFORGE_TERMINAL_BACKEND` | Alias interno de `SWARMFORGE_TERMINAL` |

### Archivos de estado en runtime (`.swarmforge/`)
```
.swarmforge/
  tmux-socket      ← path del socket tmux del proyecto
  roles.tsv        ← role → worktree-name, session, agent, receive-mode
  sessions.tsv     ← index, role, session, ...
  windows.tsv      ← ventanas de terminal rastreadas
  window-ids       ← IDs de ventanas para cleanup
  daemon/
    handoffd.pid   ← PID del daemon
    handoffd.log   ← log del daemon
    stop           ← archivo señal de shutdown
```

### Herramientas de calidad instaladas en runtime por agentes
Los agentes deben instalar las últimas versiones directamente desde GitHub:
- Go: `mutate4go`, `crap4go`, `dry4go` (via `go install`)
- Clojure: `clj-mutate`, `crap4clj`, `dry4clj` (via Clojure CLI)
- Java: `mutate4java`, `crap4java`, `dry4java` (via Maven)
- Gherkin: `gherkin-parser`, `gherkin-mutator` (desde `Acceptance-Pipeline-Specification`)

---

## 6. Puntos Críticos de Riesgo o Costo

### 🔴 Loop infinito de handoffs
**Riesgo:** Un agente en posición intermedia de la cadena que no detecta "ya procesé este task" puede hacer forward indefinidamente entre dos roles, consumiendo tokens sin límite.
**Mitigación existente:** El nombre de task (`task:`) debe ser estable y el commit SHA validado; pero no hay lock de idempotencia explícito en la lógica de forwarding.
**Recomendación:** Verificar que los roles intermedios registren tasks procesados antes de reforwardear.

### 🔴 Fallo silencioso del daemon `handoffd`
**Riesgo:** Si `handoffd.bb` muere sin que ningún agente lo detecte, los mensajes se acumulan en `outbox/` pero nadie los entrega; el enjambre queda "vivo" pero incomunicado.
**Mitigación existente:** Log en `.swarmforge/daemon/handoffd.log`; signal de shutdown via archivo `stop`.
**Punto ciego:** No hay healthcheck activo que notifique a los agentes que el daemon cayó.

### 🟡 Instalación de herramientas en cada startup de agente
**Riesgo:** Cada agente instala `go install github.com/unclebob/mutate4go@latest` etc. al inicio, lo que implica hits de red a GitHub en cada tarea, potencialmente lento o flakey.
**Costo:** Latencia de inicio; posibles fallos si GitHub no está disponible.

### 🟡 Acumulación de archivos en `inbox/completed/` y `sent/`
**Riesgo:** No hay TTL ni rotación automática. En proyectos largos, estos directorios crecen indefinidamente.
**Costo:** Disco; posible lentitud en listados de directorio.

### 🟡 Wake-up tmux lossy por diseño
**Riesgo:** Si un agente pierde la notificación tmux (e.g., por restart o crash), no sabrá que tiene trabajo pendiente hasta que reinicie manualmente y corra `ready_for_next.sh`.
**Mitigación existente:** El protocolo indica que en restart, el agente debe correr `ready_for_next.sh`. Pero si el agente no reinicia, queda bloqueado esperando.

### 🟡 Worktrees huérfanos si el enjambre falla mid-startup
**Riesgo:** Si `swarmforge.sh` falla después de crear algunos worktrees pero antes de registrar todos, el estado en `.swarmforge/` queda inconsistente.
**Mitigación existente:** `close-swarm` intenta limpiar basándose en `sessions.tsv` y `windows.tsv`, pero worktrees bajo `.worktrees/` deben eliminarse manualmente con `git worktree remove`.

### 🟢 Aislamiento de socket tmux
**Positivo:** El uso de socket por proyecto evita interferencias entre swarms corriendo simultáneamente en la misma máquina.

### 🟢 Commit SHA canónico de 10 chars
**Positivo:** `swarm_handoff.sh` valida y canonicaliza el SHA antes de encolarlo, previniendo handoffs con refs ambiguas o inválidas.

---

## Referencias de Archivos Clave

| Archivo | Propósito |
|---------|-----------|
| `swarmforge/swarmforge.conf` | Topología del enjambre (por branch runnable) |
| `swarmforge/scripts/swarmforge.bb` | Launcher principal (Babashka) |
| `swarmforge/scripts/handoffd.bb` | Daemon de entrega de handoffs |
| `swarmforge/scripts/swarm_handoff.bb` | Validación y encolado de handoffs salientes |
| `swarmforge/scripts/swarm-cleanup.sh` | Destrucción del enjambre |
| `swarmforge/scripts/swarm-terminal-adapter.sh` | Routing de backends de terminal |
| `swarmforge/scripts/terminal-adapters/*.sh` | Implementaciones por terminal |
| `swarmforge/scripts/swarm-window-watchdog.bb` | Recuperación de ventanas cerradas |
| `swarmforge/constitution/articles/` | Reglas base de comportamiento de agentes |
| `swarmforge/roles/<role>.prompt` | Prompt específico por rol (en branch runnable) |
| `.swarmforge/` | Estado de runtime (gitignored) |
| `close-swarm` | Script top-level para detener el enjambre |
