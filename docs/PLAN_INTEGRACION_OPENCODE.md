# Plan de Integración: opencode como backend en SwarmForge

> Generado: 2026-08-11  
> Versión opencode analizada: 1.17.11  
> Enfoque elegido: **A — Simple (`--prompt`, inyección a nivel usuario)**

---

## Contexto

SwarmForge exige que cualquier backend cumpla este contrato:

| Requisito | Descripción |
|-----------|-------------|
| CLI residente | El proceso permanece en TUI esperando input (no sale tras cada mensaje) |
| Inyección de prompt inicial | SwarmForge escribe instrucciones de constitución/rol y las pasa al arrancar |
| Wake-ups vía `tmux send-keys` | El daemon envía texto + `C-m`/`C-j`; el TUI debe aceptarlo como nuevo mensaje |
| Working directory por worktree | Cada agente corre aislado en `.worktrees/<role>/` |

`write-agent-instruction-file!` escribe en `<prompts-dir>/<role>.md` únicamente dos líneas:
```
Read swarmforge/constitution.prompt, then read every file it refers to recursively, and obey all of those instructions.
Read swarmforge/roles/<role>.prompt, then read every file it refers to recursively, and follow all of those instructions.
```

---

## Análisis de Compatibilidad opencode v1.17.11

| Requisito SwarmForge | Soporte opencode | Notas |
|----------------------|-----------------|-------|
| CLI residente (TUI) | ✅ | `opencode [project]` queda en TUI esperando input |
| Working dir por worktree | ✅ | Positional `[project]` acepta el path del worktree |
| Inyección de prompt inicial | ✅ (usuario) | `--prompt "$(cat file)"` — no es system-level |
| Wake-up por `tmux send-keys` | ✅ | TUI acepta texto + Enter como nuevo mensaje |
| Selección de modelo | ✅ | `-m provider/model` en TUI (ej: `anthropic/claude-sonnet-4-6`) |
| Auto-aprobación de permisos | ⚠️ | No existe `--dangerously-skip-permissions` en TUI; solo en `opencode run` |
| System prompt real | ⚠️ | Solo vía Enfoque B (agentes en `opencode.jsonc`); no aplicado aquí |
| `check-backend-dependencies!` | ✅ | Ya funciona genéricamente con `command-exists?` |

### Limitación principal
`--prompt` inyecta el texto como mensaje de *usuario*, no del *sistema*. En sesiones muy largas el modelo puede deprioritizarlo. Para tareas acotadas (el caso de SwarmForge) esto es aceptable.

### Alternativa futura (Enfoque B)
Si la limitación resulta problemática, el Enfoque B escribe definiciones de agentes en `opencode.jsonc` del proyecto antes del startup, usando el campo `prompt` de cada agente como system prompt real. Requiere que SwarmForge gestione `opencode.jsonc`, lo que implica leer/mergear config existente del usuario.

---

## Plan de Implementación — Enfoque A

### Archivos a modificar

| Archivo | Cambios |
|---------|---------|
| `swarmforge/scripts/swarmforge.bb` | 2 cambios puntuales (whitelist + case branch) |

### Cambio 1 — Whitelist de agentes válidos (línea 169)

**Antes:**
```clojure
(when-not (#{"claude" "codex" "copilot" "grok"} agent)
  (fail! (str red "Error:" reset " Unsupported agent '" agent "' for role '" role "'")))
```

**Después:**
```clojure
(when-not (#{"claude" "codex" "copilot" "grok" "opencode"} agent)
  (fail! (str red "Error:" reset " Unsupported agent '" agent "' for role '" role "'")))
```

### Cambio 2 — Branch `opencode` en `launch-command` (líneas 338–342)

**Antes (4 branches):**
```clojure
(case agent
  "claude"  (str "claude --append-system-prompt-file " (sq (str prompt-file)) " --permission-mode acceptEdits -n " (sq (str "SwarmForge " display)) " " (extra-args-prefix row) "\"$(cat " (sq (str prompt-file)) ")\"")
  "codex"   (str "codex -C " (sq (str role-worktree)) " " (extra-args-prefix row) "\"$(cat " (sq (str prompt-file)) ")\"")
  "copilot" (str "copilot -C " (sq (str role-worktree)) " --name " (sq (str "SwarmForge " display)) " " (extra-args-prefix row) "-i \"$(cat " (sq (str prompt-file)) ")\"")
  "grok"    (str "grok --cwd " (sq (str role-worktree)) " " (grok-permission-prefix row) (extra-args-prefix row) "--rules \"$(cat " (sq (str prompt-file)) ")\" --verbatim \"$(cat " (sq (str prompt-file)) ")\""))
```

**Después (5 branches — añadir al final del case):**
```clojure
(case agent
  "claude"   (str "claude --append-system-prompt-file " (sq (str prompt-file)) " --permission-mode acceptEdits -n " (sq (str "SwarmForge " display)) " " (extra-args-prefix row) "\"$(cat " (sq (str prompt-file)) ")\"")
  "codex"    (str "codex -C " (sq (str role-worktree)) " " (extra-args-prefix row) "\"$(cat " (sq (str prompt-file)) ")\"")
  "copilot"  (str "copilot -C " (sq (str role-worktree)) " --name " (sq (str "SwarmForge " display)) " " (extra-args-prefix row) "-i \"$(cat " (sq (str prompt-file)) ")\"")
  "grok"     (str "grok --cwd " (sq (str role-worktree)) " " (grok-permission-prefix row) (extra-args-prefix row) "--rules \"$(cat " (sq (str prompt-file)) ")\" --verbatim \"$(cat " (sq (str prompt-file)) ")\"")
  "opencode" (str "opencode " (sq (str role-worktree)) " --prompt \"$(cat " (sq (str prompt-file)) ")\"" " " (extra-args-prefix row)))
```

### Comando generado resultante

Para un rol `coder` en worktree `.worktrees/coder` sin extra-args:
```sh
export SWARMFORGE_ROLE='coder' \
  && export PATH='/path/.worktrees/coder/swarmforge/scripts':$PATH \
  && cd '/path/.worktrees/coder' \
  && opencode '/path/.worktrees/coder' --prompt "$(cat '/path/.swarmforge/prompts/coder.md')"
```

Con extra-args de modelo:
```sh
  && opencode '/path/.worktrees/coder' --prompt "$(cat '...')" -m anthropic/claude-sonnet-4-6
```

### Uso en `swarmforge.conf`

```conf
# Sin override de modelo
window coder opencode wt-coder task

# Con modelo explícito
window coder opencode wt-coder task -m anthropic/claude-sonnet-4-6

# Con interfaz mínima y modelo
window coder opencode wt-coder task --mini -m anthropic/claude-opus-4-8
```

---

## Riesgos y Mitigaciones

| Riesgo | Probabilidad | Mitigación |
|--------|-------------|------------|
| Agente "olvida" constitución en sesiones largas | Media | Tareas acotadas en SwarmForge minimizan esto |
| Permisos bloqueantes en TUI sin auto-aprobación | Alta | Pasar `-m anthropic/... --dangerously-skip-permissions` via extra-args usando `opencode run` wrapeado, o configurar permisos en `opencode.jsonc` del proyecto |
| Comportamiento de wake-up no probado | Alta | Requiere prueba empírica antes de uso en producción |
| Trailing whitespace en comando si extra-args vacíos | Baja | `extra-args-prefix` retorna `""` si está vacío; inofensivo |

---

## Próximos pasos post-validación

1. Probar wake-up con `tmux send-keys` a un TUI de opencode real.
2. Si auto-aprobación es necesaria, evaluar wrapper script que llame `opencode run` en loop.
3. Si system prompt resulta insuficiente, implementar Enfoque B (gestión de `opencode.jsonc`).
