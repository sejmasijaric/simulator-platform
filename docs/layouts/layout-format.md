# Layout format

A layout is one YAML file. Bundled layouts live in `src/main/resources/layouts/`; set `simulation.layouts.dir` to load more from a folder without rebuilding. Example: [fischertechnik.yaml](../../src/main/resources/layouts/fischertechnik.yaml). How the file maps to the net, with a worked example: [yaml-to-petri-net.md](yaml-to-petri-net.md); pictures: [../diagrams/petri-nets](../diagrams/petri-nets/README.md). Decisions: [ADR 0011](../adr/0011-layouts-as-timed-coloured-petri-nets.md), [ADR 0015](../adr/0015-generic-layout-interface-adapter.md).

The file is validated when the simulator starts. Unknown keys, unknown places and unbound variables stop the start with the file name in the message. Top-level keys starting with `x-` are ignored; use them to hold YAML anchors (`&name`, `*name`) for values you repeat, e.g. error responses.

## Overview

```yaml
id: fischertechnik            # used in URLs and by the control API
name: Fischertechnik Factory 4.0
description: ...
image: ...                    # photo of the physical layout for the web UI
tokens: ...                   # workpieces a user can place
places: ...                   # the net
transitions: ...
sensors: ...                  # what applications read
settings: ...                 # values the API can change, e.g. motor speeds
http: ...                     # the layout's own HTTP API
events: ...                   # messages it publishes (MQTT)
```

## Places

```yaml
places:
  oven: {capacity: 1, module: ov}               # a position
  ov_idle: {capacity: 1, initial: 1, module: ov} # machine ready while it holds a token
  cmd_ov_burn: {module: ov}                     # request queue (unlimited)
```

| Key | Meaning | Default |
|---|---|---|
| `capacity` | most tokens at once; 0 = unlimited | 0 |
| `initial` | tokens at start and after reset | 0 |
| `module` | part of the layout, to run parts on their own | none |

## Transitions

```yaml
transitions:
  ov_drive_in:
    in: [{cmd_ov_burn: job}, ov_idle, {oven: wp}]
    out: [{oven_chamber: wp}, {ov_job: job}]
    duration: 5s
  ov_burn:
    in: [{ov_job: job}, {oven_chamber: wp}]
    out: [{oven: wp}, ov_idle]
    duration: 5s
    per-unit: {parameter: time, duration: 2.4s}
```

| Key | Meaning |
|---|---|
| `in` | input arcs: `place` takes any token, `{place: name}` takes one and names it |
| `out` | output arcs: `place` creates a new token, `{place: name}` passes the named token on |
| `duration` | time from taking the inputs to putting the outputs: `45s`, `500ms`, `2.4s`, `30m`, `PT45S` |
| `per-unit` | adds `duration` for each unit of the request parameter `parameter` |
| `when` | guard: `{wp.color: red}` (token attribute), `{start: initial}` (request parameter), `"!none"` for not equal, `${color}` for a parameter's value |
| `inhibit` | places that must be empty (inhibitor arcs) |
| `result` | values handed back to the waiting request, e.g. `{color: "${wp.color}"}` |
| `priority` | higher is tried first when transitions compete; then declaration order |
| `speed` | setting(s) of the motors that move during the transition, e.g. `[vgr_m1_speed, vgr_m2_speed]`; see Settings |
| `module` | as for places |

Places in `in`, `out` and `inhibit` may be templates, `"${start}"`, filled from the request's parameters. They must be quoted in YAML.

Rules:

- A transition fires when every input finds a token (the oldest that matches its guard), its inhibitor places are empty, its guard holds and its outputs fit their capacity.
- It takes the inputs at once; the tokens are inside the transition until `duration` has passed. Sensors do not see them meanwhile.
- The request parameters of a firing are the attributes of the tokens it takes, the earlier arc winning. A task with several steps passes the request on as a job token (`{ov_job: job}`) so the later steps still see `${time}`, `${end}`.

## Settings

```yaml
settings:
  ov_m1_speed: {initial: 512, min: -512, max: 512, module: ov}
transitions:
  ov_drive_in:
    ...
    duration: 5s          # measured at the initial speed
    speed: ov_m1_speed    # or a list: the slowest motor counts
```

Whole-number values an application changes through the API ([ADR 0017](../adr/0017-settings-and-running-step-sensors.md)). Writes are clamped to `min`..`max`; a reset of the simulation restores `initial`. A transition with `speed` takes `duration × initial / |speed|` (the slowest of its settings); its `per-unit` part does not change. At speed 0 the transition does not start until the speed changes. Settings are changed by routes with `sets`/`resets` (below) or by the control API (`PUT /simulations/{id}/settings/{name}` `{"value": 256}`, `DELETE` to reset).

## Sensors

```yaml
sensors:
  ov_lb5: {occupied: oven}                             # true / false
  ov_i5: {occupied: oven, values: {true: 0, false: 1}} # as the hardware reports it
  hbw_stock: {count: high_bay_warehouse_rack}          # number of tokens
  sm_i2: {attribute: color, place: color_sensor, values: {white: 1000, red: 1350, blue: 1560, "null": 1766}}
```

A sensor can also show the step that is running, e.g. a station's `current_task`:

```yaml
  ov_task:
    running:
      ov_drive_in: "burning the workpiece for ${time} seconds"   # filled from the request
      ov_burn: "burning the workpiece for ${time} seconds"
```

It reads `""` while none of the named transitions fires.

`values` translates the derived value, written as text (`true`, `3`, `red`, `null`), to what the hardware reports. Sensors are computed when read; sensor faults apply on top.

## HTTP interface

```yaml
http:
  envelope: '{"attributes": ${body}, "link": "${request.url}", ...}'   # optional, wraps every success
  routes:
    ov_burn:
      get: /ov/burn
      parameters:
        machine: {required: true, values: [ov_1], missing: *no_machine, invalid: *wrong_machine}
        time: {default: 2, integer: true, invalid: *not_an_integer}
      command: cmd_ov_burn
      returns-after: [ov_burn, ov_burn_empty]
      timeout: 30m
      body: '[]'
    ov_status_of_light_barrier:
      get: /ov/status_of_light_barrier
      parameters: ...
      reads: ov_lb${lb}
      body: '[{"interrupted": ${read}}]'
```

- A route that has `sets` (`{"vgr_m${motor}_speed": "${speed}"}`) or `resets` (a list of settings) is a **setting** route: it changes settings, fires nothing and answers at once. A query's `reads` may name a setting too: `reads: vgr_m${motor}_speed`.
- A route is a **command** if it has `command` (the command place to put a request token into); it answers once one of `returns-after` has fired for that request, or 504 after `timeout` (default 30 minutes). Otherwise it is a **query**: it reads the sensor `reads` and fires nothing.
- `parameters` are checked in order before anything happens. `required`, `values` (allowed values), `default`, `integer`; `missing` and `invalid` are the error answers `{status, body}`, with `${value}` for the offending value.
- Placeholders in `body`, `envelope` and errors: `${name}` request parameter, `${result.x}` result of the ending transition, `${read}` the sensor read, `${sensor.x}` any sensor, `${body}` (envelope), `${value}` (errors), `${request.url}`, `${time.start|pattern}`, `${time.end|pattern}`, `${time.elapsed|timedelta}` (Python timedelta), `${now|pattern}`, `${uuid}`. Patterns are Java `DateTimeFormatter` patterns.
- Calls go to the most recently started running simulation of the layout; without one the answer is 503.

## Events

```yaml
events:
  - mqtt: FTFactory/OV_1
    every: 500ms
    payload: '{"station": "OV_1", "timestamp": "${now|yyyy-MM-dd HH:mm:ss.SS}", "i5_light_barrier": ${sensor.ov_i5}}'
```

Published for the most recently started running simulation of the layout, with the current sensor values. Broker: `simulation.events.mqtt.broker-url`.

## Picture and workpieces

A station is shown as **out of service** in the UI while a place named `<module>_in_service` exists and is empty; let its transitions take and give back that place's token (the Fischertechnik warehouse does).

`image` is the photo the web UI shows, with the pixel position of each place or sensor on it: workpieces are drawn where their place is, places named `*_idle` (machine ready) as machine chips, sensors as dots. `tokens` are the workpieces a user can put into places from the UI.

```yaml
image:
  url: /pictures/fischertechnik.png   # file in web/public/pictures/ (not /layouts/: that path is the control API)
  width: 1428
  height: 1000
  positions: {oven: [865, 318], ov_lb5: [822, 318]}   # place or sensor → pixel
tokens:
  Red workpiece: {color: red}
```
