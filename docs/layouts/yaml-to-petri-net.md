# How a layout YAML is a Petri net

Every layout is one YAML file, and its `places:` and `transitions:` sections *are* the Petri net: each entry is one node, each list item in `in:` / `out:` is one arc. The rest of the file attaches the outside world to that net: `http:` puts tokens into it, `sensors:` read tokens out of it, `events:` publish what the sensors read.

```
                 application                                   application
                      │ GET /ov/burn?time=5                         ▲ GET /ov/status_of_light_barrier
                      ▼                                             │ MQTT FTFactory/OV_1
  http: route ── puts a request token into ──► places ◄── read by ── sensors: ── events:
                                                │  ▲
                                       transitions fire
                                  (timed, guarded, coloured)
```

Pictures of every net are generated from the YAML: [diagrams/petri-nets](../diagrams/petri-nets/README.md) (`python3 scripts/render-petri-nets.py`).

## From YAML to net, element by element

| YAML | Petri net element | Drawn as |
|---|---|---|
| `places: oven: {capacity: 1}` | place `oven`, at most one token | circle |
| `initial: 1` | one token in the initial marking | ● in the circle |
| `transitions: ov_burn: …` | transition `ov_burn` | dark box |
| `in: [{oven_chamber: wp}]` | input arc oven_chamber → ov_burn; the token it takes is called `wp` | arrow labelled `wp` |
| `in: [ov_idle]` | input arc without a name: takes any token | arrow |
| `out: [{oven: wp}]` | output arc ov_burn → oven, passing the *same* token `wp` on | arrow labelled `wp` |
| `out: [ov_idle]` | output arc creating a new plain token | arrow |
| `inhibit: [oven]` | inhibitor arc: fires only while `oven` is empty | dashed red line with ○ |
| `duration: 5s` | timed transition: outputs appear 5 s after the inputs were taken | "takes 5 s" |
| `per-unit: {parameter: time, duration: 2.4s}` | duration grows with the request parameter `time` | "takes 5 s + 2.4 s × time" |
| `when: {wp.color: red}` | guard on the token's colour (attribute) | `[wp.color = red]` |
| `when: {start: initial}` | guard on a request parameter | `[start = initial]` |
| `"${start}"` as a place | template place, chosen by the request's parameter | dashed circle |
| `result: {color: "${wp.color}"}` | value handed back to the waiting API call | `↩ color := …` |

**Coloured** means tokens carry data: a workpiece token has `{color: red}`, a request token carries the request's parameters (`{machine: ov_1, time: 5}`). **Timed** means a transition holds its tokens for its duration: while it fires, the tokens are in neither its input nor its output places (the oven chamber is "inside" the burn).

## Four kinds of places, one pattern

Every module of the Fischertechnik factory uses the same four kinds of places (colours as in the pictures and the UI):

| Kind | Example | Meaning |
|---|---|---|
| position (white) | `oven`, `sink_1` | where a workpiece can be; usually capacity 1 |
| machine ready (green) | `ov_idle`, initial 1 | holds one token while the machine is free. A transition that takes it has the machine; giving it back frees it. This is what makes requests wait, like the physical controller's queue. |
| request queue (blue) | `cmd_ov_burn` | the route's `command`: every API call puts one request token here |
| job (purple) | `ov_job` | a request in progress, passed between the steps of a multi-step task, so later steps still see its parameters |

## Worked example: the oven

![The oven module as a Petri net](../diagrams/petri-nets/fischertechnik-ov.svg)

```yaml
places:
  oven: {capacity: 1, module: ov}               # the platform in front of the oven
  oven_chamber: {capacity: 1, module: ov}       # inside
  ov_idle: {capacity: 1, initial: 1, module: ov} # oven is ready
  cmd_ov_burn: {module: ov}                     # requests from GET /ov/burn
  ov_job: {capacity: 1, module: ov}             # the request while it is being burnt

transitions:
  ov_drive_in:                                  # needs: a request, the oven free, a workpiece on the platform
    in: [{cmd_ov_burn: job}, ov_idle, {oven: wp}]
    out: [{oven_chamber: wp}, {ov_job: job}]
    duration: 5s
  ov_burn:                                      # burns, drives out, frees the oven
    in: [{ov_job: job}, {oven_chamber: wp}]
    out: [{oven: wp}, ov_idle]
    duration: 5s
    per-unit: {parameter: time, duration: 2.4s}
  ov_burn_empty:                                # the real oven also "burns" with nothing on the platform
    in: [{cmd_ov_burn: job}, ov_idle]
    inhibit: [oven]
    out: [ov_idle]
    duration: 10s
    per-unit: {parameter: time, duration: 2.4s}

http:
  routes:
    ov_burn:
      get: /ov/burn
      command: cmd_ov_burn                       # where the request token goes
      returns-after: [ov_burn, ov_burn_empty]    # the HTTP call answers when one of these fired for it
```

What happens on `GET /ov/burn?machine=ov_1&time=5`, with a red workpiece on `oven`:

| Sim time | Marking (where the tokens are) | What fired |
|---|---|---|
| 0 s | `cmd_ov_burn` = request{time 5} · `ov_idle` = ● · `oven` = wp{red} | the API put the request token in |
| 0 s | — tokens are inside `ov_drive_in` — | `ov_drive_in` is enabled (all three inputs present) and starts; `ov_burn_empty` is not, its inhibitor `oven` is occupied. The workpiece left `oven`, so sensor `ov_lb5` (occupied: oven) reads false from now on |
| 5 s | `oven_chamber` = wp{red} · `ov_job` = request{time 5} | `ov_drive_in` completes |
| 5 s | — inside `ov_burn` for 5 s + 5 × 2.4 s = 17 s — | `ov_burn` starts |
| 22 s | `oven` = wp{red} · `ov_idle` = ● | `ov_burn` completes: it is in the route's `returns-after`, so the HTTP call answers `[]`; `ov_lb5` reads true again |

A second request arriving at 3 s simply waits in `cmd_ov_burn`, because `ov_idle` is empty: the queue of the physical controller, without any extra code.

## How the rest of the file hangs off the net

- **Sensors** are computed from the marking when read: `ov_lb5: {occupied: oven}` is true while `oven` holds a token; `hbw_stock: {count: high_bay_warehouse_rack}` is the number of tokens; `sm_i2: {attribute: color, place: color_sensor, values: {...}}` translates the workpiece's colour into the raw brightness the hardware reports.
- **Queries** (`reads:`) answer with a sensor value and fire nothing.
- **Events** (MQTT) publish sensor values every 500 ms.
- **Faults** need nothing in the YAML: any transition can be blocked, slowed, lose or misroute its tokens; any sensor can be stuck, drop out, lie or lag (ADR 0013).
- **Modules** (`module: ov`) group places and transitions, so one station can run on its own.

## Why a Petri net and not BPMN

The layout is a set of shared machines that wait for commands and serve several workpieces at once; that is what tokens, resource places and concurrent transitions describe. BPMN describes one case from start to end, which is what the *application* is (e.g. the Camunda process). The net models what is physically possible; the application decides the order. Details: [ADR 0011](../adr/0011-layouts-as-timed-coloured-petri-nets.md).
