# ADR 0011: Layouts are timed, coloured Petri nets declared in YAML

Status: Accepted (2026-11-23). Supersedes [0001](0001-java-coded-layouts-and-framework-free-core.md) (layout part), [0003](0003-timed-multi-phase-actions.md) and [0004](0004-state-changes-as-proposals-committed-atomically.md).

## Context
Each layout was Java code: its own components, phases, sensors, faults and a hand-written adapter (about 2,200 lines for two layouts). Adding a layout meant programming. We want a layout to be described, not programmed: what the physical layout can do, what its sensors show and what its interface looks like.

Options considered:

| | Java layouts (before) | BPMN | Timed coloured Petri net | Events only (replay) |
|---|---|---|---|---|
| New layout without code | no | partly | mostly | yes |
| Reacts to commands | yes | awkward | yes | no |
| Several workpieces, shared machines | yes | awkward (one case per instance) | yes (tokens, resource places) | – |
| Generic faults | no | partly | yes | sensor faults only |
| Formal analysis (deadlock, boundedness) | no | limited | yes | no |

BPMN describes one case from start to end and is what the *application* is written in. The layout itself is a set of shared machines that wait for commands and serve several workpieces at once, which is what Petri nets describe. Event replay cannot react to commands, so an application could not run against it.

## Decision
- A layout is one YAML file (`src/main/resources/layouts/*.yaml`, format in [layout-format.md](../layouts/layout-format.md)): a Petri net, a sensor schema, the layout's interface (HTTP routes, published messages), a picture with place positions, and workpiece templates.
- The net models what is physically possible, not a production order. The application decides the order.
- Places are physical: positions, machine states (a resource place holds one token while the machine is ready) and the controller's request queue (a command place). Tokens are workpieces with attributes (colour) or requests with parameters.
- Transitions are timed: a firing takes its input tokens at once and gives its outputs back after its duration, which may grow with a request parameter (`per-unit`). Firing is atomic: all inputs or none, all outputs at once.
- Extensions used: guards (`when`, equality or `!`-inequality on token attributes and request parameters), inhibitor arcs (`inhibit`), template places (`${start}`) filled from the request, and results (`result`) handed back to the waiting request.
- A request that takes several steps passes itself on as a job token, so later steps still see its parameters.
- Transitions are tried by priority, then declaration order; tokens are taken oldest first. Runs are deterministic.

## Consequences
- Adding a layout means writing YAML; the Fischertechnik factory needs no Java at all (0 hooks).
- The YAML is validated on load (unknown places, unbound variables, typos in keys), with the file name in the message.
- Template places and guards bend the formalism slightly; a strict analysis would need the templates expanded into one transition per value.
- Sensors cannot see tokens inside a running transition (e.g. a workpiece being burnt); layouts model visible intermediate positions as places.
