# ADR 0017: Settings for values an application changes, and sensors for the running step

Status: Accepted (2026-11-26). Extends [0011](0011-layouts-as-timed-coloured-petri-nets.md) and [0014](0014-sensors-are-read-on-demand.md).

## Context
Two things of the physical factory could not be described in a layout:
- **Motor speeds.** `set_motor_speed` changes a motor's PWM (−512…512, clamped); every later movement of that motor takes longer or shorter. A speed is not a token: nothing consumes or produces it, the API overwrites it, and it changes how long transitions take.
- **Task texts.** Each station publishes `current_task` and `current_sub_task`, the text of what it is doing now, filled from the request (e.g. `burning the workpiece for 2 seconds`). Sensors derive their value from the marking (ADR 0014), but a step in progress has its tokens inside the transition, invisible to the marking.

## Decision
- **Settings** are named whole-number values per simulation, declared in the layout: `settings: {vgr_m1_speed: {initial: 512, min: -512, max: 512, module: vgr}}`. Writes are clamped to `min`..`max`. A reset of the simulation puts them back to `initial`.
- **Setting routes**: a route with `sets` (setting name → value, both templates over the request parameters) or `resets` (setting names) changes settings and fires nothing (route kind `SETTING`). A query's `reads` may name a setting as well as a sensor. The control API offers the same (`PUT`/`DELETE /simulations/{id}/settings/{name}`) for the UI. Every change is logged (`SETTING_CHANGED`).
- **Speed-dependent durations**: a transition's `speed` lists the settings of the motors that move during it. Its base `duration`, measured at the settings' initial value, becomes `duration × initial / |slowest speed|`; the `per-unit` part (waiting, blinking, milling seconds) is not motor time and stays. With speed 0 the motor stands still and the transition is not enabled until the speed changes.
- **Running sensors**: a sensor `{running: {transition: "text ${parameter}"}}` shows the text of the first firing in progress among the named transitions, filled from that firing's request parameters (and `${variable.attribute}` of its bound tokens); `""` while none of them runs. It is computed on demand like every sensor.

## Consequences
- Motor speeds work for any layout without code: the Fischertechnik layout declares nine motors and their three endpoints per machine; durations follow the speed.
- The published messages show the controllers' task texts, so an application that reads `current_task` sees what it would see in the lab.
- Settings and speed-dependent durations are, like guards and template places, an extension of the formalism: for a formal analysis, durations become parameters of the net.
- The scaling is linear by assumption; whether the physical motors behave so at low PWM has to be measured. A negative speed counts as the same pace in reverse.
- Running sensors depend on the steps of the net; where a controller sets several sub-tasks within one transition, the layout shows one text for the whole step.
