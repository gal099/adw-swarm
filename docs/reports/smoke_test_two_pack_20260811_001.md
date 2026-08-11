# Smoke Test — Two-Pack con opencode

**ID:** smoke_test_two_pack_20260811_001  
**Fecha:** 2026-08-11  
**Ejecutado por:** Claude Code (asistente)  
**Resultado global:** ✅ ÉXITO COMPLETO (ciclo cerrado en segunda ejecución)

---

## Resumen Ejecutivo

| Campo | Valor |
|-------|-------|
| Fecha | 2026-08-11 |
| Backend | `opencode` v1.17.11 |
| Modelo ejecutado | DeepSeek V4 Flash Free (default de opencode) |
| Workflow | `two-pack` (`coder` → `cleaner`) |
| Rama git | `two-pack` |
| Resultado | ÉXITO — ciclo completo de handoff verificado |

El objetivo de esta prueba fue validar la integración de `opencode` como backend en SwarmForge, específicamente el flujo completo `coder` → `cleaner` → `coder` en el workflow two-pack.

La prueba se ejecutó en **dos sesiones consecutivas**:
- **Sesión 1 (18:22):** El coder implementó la función y envió el handoff al cleaner. El enjambre fue detenido manualmente mientras el cleaner procesaba, dejando el handoff en `in_process`.
- **Sesión 2 (relanzamiento posterior):** Al reiniciar, el cleaner retomó automáticamente el trabajo pendiente desde `in_process`, completó el análisis de calidad completo, hizo su commit y envió el handoff de vuelta al coder. El coder mergeó y cerró el ciclo. **Ciclo two-pack completo y autónomo confirmado.**

---

## Componentes Validados

| Componente | Versión | Estado | Notas |
|------------|---------|--------|-------|
| `bb` (Babashka) | v1.13.219 | ✅ OK | Instalado vía Homebrew antes del test |
| `tmux` | v3.7 | ✅ OK | Socket aislado por proyecto en `/tmp/swarmforge-juanbaez/` |
| `opencode` | v1.17.11 | ✅ OK | Aceptado por el whitelist de `swarmforge.bb` |
| `git` | v2.50.1 (Apple Git-155) | ✅ OK | Worktrees creados sin errores |
| Daemon `handoffd.bb` | — | ✅ OK | PID 5199, `caffeinate` activo (sleep prevention macOS) |
| Worktree `master` (coder) | — | ✅ OK | Coder corrió en el directorio principal del proyecto |
| Worktree `wt-cleaner` (cleaner) | — | ✅ OK | Creado en `.worktrees/wt-cleaner/` |
| Socket tmux | — | ✅ OK | `/tmp/swarmforge-juanbaez/1923759490.sock` |

### Startup del enjambre

```
SwarmForge v1.0 Starting
Started handoff daemon with OS sleep prevention.
Starting agents...
  [Coder] started in session swarmforge-coder
  [Cleaner] started in session swarmforge-cleaner
SwarmForge is ready.
```

---

## Flujo de Handoff Ejecutado

### Fase 1 — Startup de agentes

Ambos agentes leyeron la constitución y los prompts de rol de forma recursiva al iniciar:

- `constitution.prompt` → artículos `engineering.prompt`, `project.prompt`, `workflow.prompt`
- `cleaner.prompt` → `coder.prompt`, `handoff-protocol.md`, scripts de helpers

El agente `cleaner` (primer en completar startup) ejecutó `ready_for_next.sh` → `NO_TASK`, corrió los tests baseline (`6 tests, 32 assertions, 0 failures`) y quedó idle.

Ambos agentes procuraron las herramientas de calidad al iniciar, según indica el artículo `engineering.prompt`:

| Herramienta | SHA del clone | Estado |
|-------------|---------------|--------|
| `clj-mutate` | `e27dd5d` | ✅ `bb mutate --help` OK |
| `crap4clj` | `e6e0312f` | ✅ `bb crap --help` OK |
| `dry4clj` | `5994ef2b` | ✅ `bb dry4clj --help` OK |

### Fase 2 — Tarea asignada al coder

**Método de inyección:** `tmux send-keys` directo a la sesión `swarmforge-coder`. Este es un atajo manual usado solo para el smoke test — el texto se escribió en el TUI de opencode como si fuera un humano tipeando, por lo que **no quedó registrado en ningún archivo ni en el trail de handoffs**.

En un flujo de producción, las tareas externas deberían enviarse como un handoff draft procesado por `swarm_handoff.sh`, lo que sí genera trazabilidad en `sent/`.

Texto exacto enviado al agente:

```
Implement a Clojure function called `add` in a new namespace `swarmforge.math`
(file: src/swarmforge/math.clj) that takes two numbers and returns their sum.
Add a corresponding Speclj test in test/swarmforge/math_test.clj. Keep it minimal.
When done, commit and send a git_handoff to cleaner with task name: smoke-test-add-fn
```

### Fase 3 — Generación del handoff por el coder

El coder implementó la función, ejecutó los tests y llamó a `swarm_handoff.sh`. El handoff generado:

```
id: 20260811T182209Z_000001_from_coder
from: coder
to: cleaner
priority: 50
type: git_handoff
role: coder
task: smoke-test-add-fn
commit: e207e53d84
created_at: 2026-08-11T18:22:09.153847Z

Re-read your role and constitution.

merge_and_process coder e207e53d84
```

**Archivo en `sent/`:** `50_20260811T182209Z_000001_from_coder_to_cleaner.handoff`

### Fase 4 — Entrega por el daemon

`handoffd.bb` detectó el archivo en `outbox/`, lo copió al inbox del cleaner con headers `recipient` y `enqueued_at`, envió el wake-up tmux genérico y movió el original a `sent/`.

**Ruta en inbox del cleaner:** `.worktrees/wt-cleaner/.swarmforge/handoffs/inbox/in_process/batch_20260811T182213Z_000001/`

### Fase 5 — Recepción y procesamiento por el cleaner

El cleaner recibió el wake-up tmux, ejecutó `ready_for_next.sh` (modo `batch`), tomó el handoff y lo movió a `in_process/`. Procedió a:

1. Ejecutar `merge_and_process coder e207e53d84` para integrar el commit del coder.
2. Explorar la estructura del proyecto y los tests existentes.
3. Configurar el pipeline de coverage (Cloverage + LCOV) requerido por `crap4clj`.
4. Ejecutar análisis CRAP y DRY sobre el código mergeado.

El enjambre fue detenido manualmente durante la fase de análisis CRAP del cleaner (primera sesión). El handoff quedó en `in_process/batch_20260811T182213Z_000001/`.

### Fase 5b — Reanudación automática en segunda sesión

Al relanzar el enjambre con `./swarm`, el cleaner detectó el handoff pendiente en `in_process/` via `ready_for_next.sh` y retomó el trabajo sin intervención manual. Este comportamiento confirma la resiliencia del protocolo ante reinicios.

**Investigación del cleaner — problema de convención `spec/` vs `test/`:**

Al ejecutar mutation testing con `clj-mutate`, el cleaner detectó que el mutante `+` → `-` reportaba "sobrevivido" — un resultado imposible matemáticamente (`(add 2 3)` con `-` da `-1`, no `5`). Investigó la causa:

- `clj-mutate` crea un worker overlay que solo espeja el directorio `spec/` (convención de Speclj), no `test/` (donde el coder colocó los tests).
- El worker ejecutaba 0 ejemplos → todos los mutantes "sobrevivían" falsamente.
- El cleaner verificó la hipótesis construyendo un entorno manual en `/tmp`, aplicando la mutación a mano y enlazando `test/` → el spec falló correctamente: `1 examples, 1 failures`.
- **Conclusión:** el mutante sí es matado; el falso "survived" es una limitación de overlay del tool cuando el proyecto no usa la convención `spec/`.

**Recomendación derivada:** los specs de Speclj deben ir en `spec/`, no en `test/`, para compatibilidad con `clj-mutate`. Agregar esta instrucción a `engineering.prompt`.

**Commit del cleaner:** `2c10d7bb35`

```
Harden math/add with mutation manifest and ignore tool artifacts
```

Cambios:
- `.gitignore`: agregó `target/` y `tmp/` para excluir artefactos de tools
- `src/swarmforge/math.clj`: añadió footer de manifest de `clj-mutate` con hash de módulo y formas auditadas

**Reporte de calidad del cleaner:**

| Métrica | Resultado |
|---------|-----------|
| Coverage | 100% forms/lines |
| CRAP score | 1.0 (umbral: 6) ✅ |
| DRY | Sin duplicación ✅ |
| Estructura | Módulo puro testeable, sin problemas de boundary ✅ |
| Mutation | 1/1 mutantes matados (`+` → `-`) ✅ |
| Tests | `bb spec` 1/0 · `bb test` 6/0 ✅ |

### Fase 6 — Handoff de cleaner a coder y cierre del ciclo

El cleaner encoló el handoff de retorno al coder con **prioridad 00** (más alta):

```
type: git_handoff
to: coder
priority: 00
task: smoke-test-add-fn
commit: 2c10d7bb35
```

**Archivo en `sent/` del cleaner:** `00_20260811T191512Z_000001_from_cleaner_to_coder.handoff`

Llamó a `done_with_current.sh` → batch marcado `completed` → `NO_TASK`.

### Fase 7 — Merge final por el coder

El coder recibió el wake-up del daemon, ejecutó `ready_for_next.sh`, mergeó el commit `2c10d7bb35` del cleaner (fast-forward) y verificó:

```
bb spec: 1 examples, 0 failures, 1 assertions
bb test: 6 tests, 32 assertions, 0 failures
```

**Decisión de protocolo del coder:** el commit del cleaner era manifest-only + `.gitignore` — sin cambio funcional — por lo que correctamente **no reenvió** un `git_handoff` (lo habría hecho circular sin valor). Llamó directo a `done_with_current.sh` → `COMPLETED → NO_TASK`. Idle en 47.6s.

**El loop two-pack quedó completamente cerrado de forma autónoma.**

### Trail completo de handoffs

| Archivo | Dirección | Estado final |
|---------|-----------|-------------|
| `50_20260811T182209Z_000001_from_coder_to_cleaner.handoff` | coder → cleaner | `sent/` (master) · `completed/batch_...` (cleaner) |
| `00_20260811T191512Z_000001_from_cleaner_to_coder.handoff` | cleaner → coder | `sent/` (cleaner) · `completed/` (master) |

### Timeline de la segunda sesión

```
Relanzamiento  → cleaner retoma in_process automáticamente
               → investiga falso "survived" en clj-mutate (~30 min, ~160K tokens)
               → confirma mutante realmente matado vía test manual en /tmp
               → commit 2c10d7bb35 (manifest + .gitignore)
               → swarm_handoff.sh → handoff priority 00 a coder (191512Z)
               → done_with_current.sh → COMPLETED/NO_TASK
               → daemon entrega handoff al coder
               → coder mergea, verifica tests, done_with_current.sh → NO_TASK (47.6s)
               → ambos agentes idle
```

---

## Hallazgos Clave y Configuración

### Uso de Modelo Gratuito — Comportamiento Esperado y Deseado

opencode utilizó **DeepSeek V4 Flash Free** como modelo, ignorando el flag `-m anthropic/claude-sonnet-4-6` especificado en `swarmforge.conf`. Este es el modelo default configurado en el perfil de opencode del usuario.

**Esto es el comportamiento deseado para el uso habitual:** el modelo gratuito reduce el costo de API a cero para tareas de desarrollo rutinario. El flag `-m` puede usarse para escalar a modelos de pago en tareas que lo requieran (razonamiento complejo, código crítico), pero no es obligatorio.

**Nota técnica:** El flag `-m` en el modo TUI de opencode establece el modelo de la *sesión nueva*. Si opencode tiene un modelo default configurado globalmente, puede tomar precedencia. Para forzar el modelo, puede ser necesario configurarlo en `~/.config/opencode/opencode.jsonc` bajo `"model"`.

### Comportamiento del Cleaner — Exhaustividad vs Velocidad

El agente `cleaner` siguió al pie de la letra el artículo `engineering.prompt`, realizando:

- Clonado fresco de tres repos de herramientas desde GitHub en `./tmp/tools/`
- Configuración de pipeline de coverage (Cloverage + LCOV) con deps.edn
- Intento de instalación del tap `clojure/tools` via Homebrew
- Análisis CRAP, DRY y de mutación

**Tiempo de procesamiento:** >5 minutos para una tarea mínima (`add` de dos números).

**Recomendación:** Para ciclos de desarrollo más ágiles, crear un artículo `swarmforge/constitution/articles/local-engineering.prompt` en el branch de trabajo que reemplace o aliviane el artículo `engineering.prompt` compartido. Por ejemplo:

```text
# local-engineering.prompt — smoke-test / desarrollo rápido
## Herramientas de calidad
- Omitir instalación de herramientas de mutación en tareas marcadas como smoke-test.
- Ejecutar únicamente `bb test` para verificación básica; omitir CRAP y DRY salvo
  que el role prompt lo requiera explícitamente.
```

Esto no modifica el comportamiento en producción (el artículo `engineering.prompt` del `main` permanece intacto) y permite iteraciones más rápidas en ramas de desarrollo.

---

## Configuración del Test

### `swarmforge/swarmforge.conf` usado

```conf
# SwarmForge two-pack — opencode backend (smoke test)
window coder opencode master task -m anthropic/claude-sonnet-4-6
window cleaner opencode wt-cleaner batch -m anthropic/claude-sonnet-4-6
```

### Modificaciones aplicadas a `swarmforge/scripts/swarmforge.bb`

**Línea 169 — Whitelist de agentes:**
```clojure
;; Antes:
(when-not (#{"claude" "codex" "copilot" "grok"} agent) ...)
;; Después:
(when-not (#{"claude" "codex" "copilot" "grok" "opencode"} agent) ...)
```

**Líneas 342-343 — Branch en `launch-command`:**
```clojure
"opencode" (str "opencode " (sq (str role-worktree))
                " --prompt \"$(cat " (sq (str prompt-file)) ")\"" 
                " " (extra-args-prefix row))
```

**Comando generado para el coder:**
```sh
export SWARMFORGE_ROLE='coder' \
  && export PATH='/path/swarmforge/scripts':$PATH \
  && cd '/Users/juanbaez/Documents/swarm-forge' \
  && opencode '/Users/juanbaez/Documents/swarm-forge' \
     --prompt "$(cat '/path/.swarmforge/prompts/coder.md')" \
     -m anthropic/claude-sonnet-4-6
```

---

## Próximos Pasos Recomendados

1. **Agregar instrucción de directorio de specs a `engineering.prompt`:** los specs de Speclj deben ir en `spec/`, no en `test/`, para compatibilidad con el worker overlay de `clj-mutate`. El coder eligió `test/` razonablemente (es convención estándar de Clojure), pero la especificación estaba incompleta.

2. **Investigar comportamiento del flag `-m`** en opencode TUI para confirmar si el modelo puede forzarse o si debe configurarse en `opencode.jsonc`.

3. **Crear `local-engineering.prompt`** para ciclos de desarrollo ágil que no requieran análisis CRAP/mutación completo. En esta prueba el cleaner invirtió ~160K tokens en una función de 3 líneas.

4. **Evaluar Enfoque B** (agentes definidos en `opencode.jsonc` con system prompt real) si la falta de system-level prompt resulta problemática en sesiones largas.

5. **Documentar el workaround de `shared-articles/`** para evitar que `./swarm` descargue de GitHub cuando los scripts ya existen localmente en desarrollo.
