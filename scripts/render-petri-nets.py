#!/usr/bin/env python3
"""
Draws every layout's Petri net from its YAML file.

For each layout in src/main/resources/layouts/ (or the files given as arguments) it writes:

  docs/diagrams/petri-nets/<id>.svg / .png            the whole net, one box per module
  docs/diagrams/petri-nets/<id>-<module>.svg / .png   each module on its own
  docs/diagrams/petri-nets/README.md                  an index of all pictures

Needs Python 3 with PyYAML and Graphviz (`dot`). Run from the repository root:

  python3 scripts/render-petri-nets.py

Notation (also drawn as a legend in every picture):
  circle        place; its fill tells what it stands for (position, machine ready, request queue, job)
  ● in a circle initial token; "≤ n" is the capacity
  box           transition, with its duration and guard
  arrow         arc, labelled with the token variable it binds (wp = workpiece, job = request)
  o-arrow head  inhibitor arc: the transition only fires while the place is empty
  dashed circle template place such as ${start}: filled from the request's parameters
"""

from __future__ import annotations

import html
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
LAYOUTS = ROOT / "src" / "main" / "resources" / "layouts"
DOCS = ROOT / "docs" / "diagrams" / "petri-nets"

# colours of the place kinds
KIND_STYLE = {
    "position": {"fill": "#FFFFFF", "line": "#5F5E5A", "label": "position of a workpiece"},
    "resource": {"fill": "#EAF3DE", "line": "#3B6D11", "label": "machine ready (resource)"},
    "command": {"fill": "#E6F1FB", "line": "#185FA5", "label": "request queue (from the API)"},
    "job": {"fill": "#EEEDFE", "line": "#534AB7", "label": "request in progress (job)"},
}
TRANSITION_FILL = "#2C2C2A"
FONT = "Helvetica"


# ---------------------------------------------------------------- reading the YAML

@dataclass
class Arc:
    place: str
    variable: str | None

    @property
    def template(self) -> bool:
        return "${" in self.place


@dataclass
class Transition:
    name: str
    inputs: list[Arc]
    outputs: list[Arc]
    inhibit: list[str]
    duration: str
    per_unit: dict | None
    when: dict
    result: dict
    priority: int
    module: str | None


@dataclass
class Place:
    name: str
    capacity: int
    initial: int
    module: str | None
    kind: str = "position"


@dataclass
class Net:
    id: str
    name: str
    places: dict[str, Place]
    transitions: list[Transition]
    commands: dict[str, str] = field(default_factory=dict)  # command place -> route path

    @property
    def modules(self) -> list[str]:
        seen: list[str] = []
        for item in [*self.places.values(), *self.transitions]:
            if item.module and item.module not in seen:
                seen.append(item.module)
        return seen


def arcs(values) -> list[Arc]:
    result = []
    for value in values or []:
        if isinstance(value, dict):
            (place, variable), = value.items()
            result.append(Arc(str(place), str(variable)))
        else:
            result.append(Arc(str(value), None))
    return result


def load(path: Path) -> Net:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    places = {
        name: Place(name, int((spec or {}).get("capacity", 0)), int((spec or {}).get("initial", 0)),
                    (spec or {}).get("module"))
        for name, spec in (data.get("places") or {}).items()
    }
    transitions = []
    for name, spec in (data.get("transitions") or {}).items():
        inhibit = spec.get("inhibit") or []
        transitions.append(Transition(
            name=name,
            inputs=arcs(spec.get("in")),
            outputs=arcs(spec.get("out")),
            inhibit=[inhibit] if isinstance(inhibit, str) else list(inhibit),
            duration=str(spec.get("duration", "0s")),
            per_unit=spec.get("per-unit"),
            when=spec.get("when") or {},
            result=spec.get("result") or {},
            priority=int(spec.get("priority", 0)),
            module=spec.get("module"),
        ))
    commands = {}
    for route in ((data.get("http") or {}).get("routes") or {}).values():
        if route.get("command"):
            path_ = next((route[m] for m in ("get", "post", "put", "delete") if m in route), "")
            commands[route["command"]] = path_
    net = Net(data["id"], data.get("name", data["id"]), places, transitions, commands)
    classify(net)
    return net


def classify(net: Net) -> None:
    """Command places are what the API writes into; jobs carry a request on; resources are 'machine ready'."""
    for place in net.places.values():
        if place.name in net.commands:
            place.kind = "command"
        elif place.initial > 0 and place.capacity == 1:
            place.kind = "resource"
    changed = True
    while changed:
        changed = False
        for transition in net.transitions:
            request_vars = {arc.variable for arc in transition.inputs
                            if arc.variable and arc.place in net.places
                            and net.places[arc.place].kind in ("command", "job")}
            for arc in transition.outputs:
                place = net.places.get(arc.place)
                if place and arc.variable in request_vars and place.kind == "position":
                    place.kind = "job"
                    changed = True


# ---------------------------------------------------------------- labels

def short_duration(transition: Transition) -> str:
    text = transition.duration
    if transition.per_unit:
        text += f" + {transition.per_unit.get('duration')}·{transition.per_unit.get('parameter')}"
    return "" if text in ("0s", "0", "PT0S") else text


def guard_text(transition: Transition) -> str:
    parts = []
    for key, value in transition.when.items():
        value = str(value)
        parts.append(f"{key} ≠ {value[1:]}" if value.startswith("!") else f"{key} = {value}")
    return ", ".join(parts)


def result_text(transition: Transition) -> str:
    return ", ".join(f"{key} := {value}" for key, value in transition.result.items())


def transition_label(transition: Transition) -> str:
    rows = [f'<TR><TD><FONT POINT-SIZE="11" COLOR="#FFFFFF"><B>{html.escape(transition.name)}</B></FONT></TD></TR>']
    duration = short_duration(transition)
    if duration:
        rows.append(f'<TR><TD><FONT POINT-SIZE="9" COLOR="#D3D1C7">takes {html.escape(duration)}</FONT></TD></TR>')
    guard = guard_text(transition)
    if guard:
        rows.append(f'<TR><TD><FONT POINT-SIZE="9" COLOR="#FAC775">[{html.escape(guard)}]</FONT></TD></TR>')
    result = result_text(transition)
    if result:
        rows.append(f'<TR><TD><FONT POINT-SIZE="9" COLOR="#9FE1CB">↩ {html.escape(result)}</FONT></TD></TR>')
    return f'<<TABLE BORDER="0" CELLBORDER="0" CELLSPACING="0" CELLPADDING="1">{"".join(rows)}</TABLE>>'


def place_label(place: Place) -> str:
    tokens = "●" * min(place.initial, 5) if place.initial else ""
    return tokens


def place_xlabel(place: Place, net: Net) -> str:
    extra = []
    if place.capacity:
        extra.append(f"≤ {place.capacity}")
    if place.kind == "command" and place.name in net.commands:
        extra.append(net.commands[place.name])
    suffix = f'<BR/><FONT POINT-SIZE="8" COLOR="#8B8A85">{html.escape("  ".join(extra))}</FONT>' if extra else ""
    return f'<<FONT POINT-SIZE="10">{html.escape(place.name)}</FONT>{suffix}>'


# ---------------------------------------------------------------- the graph

def node_id(kind: str, name: str, module: str | None = None) -> str:
    if kind == "x":
        return f"x:{module or ''}:{name}"
    return f"{kind}:{name}"


@dataclass
class Edge:
    source: str
    target: str
    kind: str  # in | out | inhibit
    label: str


def graph(net: Net, module: str | None):
    """Nodes and edges of the whole net, or of one module (plus the places it touches)."""
    transitions = [t for t in net.transitions if module is None or t.module == module]
    places: dict[str, Place] = {}
    templates: dict[str, tuple[str, str | None]] = {}
    edges: list[Edge] = []

    def endpoint(place_name: str, owner: Transition) -> str:
        if "${" in place_name:
            key = node_id("x", place_name, owner.module)
            templates[key] = (place_name, owner.module)
            return key
        places[place_name] = net.places[place_name]
        return node_id("p", place_name)

    for transition in transitions:
        tid = node_id("t", transition.name)
        for arc in transition.inputs:
            edges.append(Edge(endpoint(arc.place, transition), tid, "in", arc.variable or ""))
        for arc in transition.outputs:
            edges.append(Edge(tid, endpoint(arc.place, transition), "out", arc.variable or ""))
        for place_name in transition.inhibit:
            edges.append(Edge(endpoint(place_name, transition), tid, "inhibit", ""))
    if module is not None:
        for place in net.places.values():
            if place.module == module:
                places.setdefault(place.name, place)
    else:
        places = dict(net.places) | places
    return transitions, places, templates, edges


def dot_source(net: Net, module: str | None, legend: bool) -> str:
    transitions, places, templates, edges = graph(net, module)
    title = net.name if module is None else f"{net.name} · module {module}"
    out = [
        "digraph net {",
        f'  graph [rankdir=LR, fontname="{FONT}", fontsize=14, labelloc=t, labeljust=l, pad=0.3, '
        f'nodesep=0.35, ranksep=0.55, newrank=true, forcelabels=true, '
        f'label=<<B>{html.escape(title)}</B><BR/><FONT POINT-SIZE="10" COLOR="#5F5E5A">'
        f'Timed coloured Petri net, generated from {html.escape(net.id)}.yaml</FONT>>];',
        f'  node [fontname="{FONT}"];',
        f'  edge [fontname="{FONT}", fontsize=9, fontcolor="#5F5E5A", color="#5F5E5A", arrowsize=0.7];',
    ]

    def place_node(place: Place) -> str:
        style = KIND_STYLE[place.kind]
        return (f'  "{node_id("p", place.name)}" [shape=circle, width=0.42, fixedsize=true, style=filled, '
                f'fillcolor="{style["fill"]}", color="{style["line"]}", penwidth=1.6, fontsize=9, '
                f'label="{place_label(place)}", xlabel={place_xlabel(place, net)}];')

    def transition_node(transition: Transition) -> str:
        return (f'  "{node_id("t", transition.name)}" [shape=box, style="rounded,filled", fillcolor="{TRANSITION_FILL}", '
                f'color="{TRANSITION_FILL}", margin="0.12,0.06", label={transition_label(transition)}];')

    def template_node(key: str, name: str) -> str:
        return (f'  "{key}" [shape=circle, width=0.42, fixedsize=true, style="dashed,filled", fillcolor="#FBFAF7", '
                f'color="#888780", label="", xlabel=<<FONT POINT-SIZE="10"><I>{html.escape(name)}</I></FONT>'
                f'<BR/><FONT POINT-SIZE="8" COLOR="#8B8A85">any position</FONT>>];')

    if module is None and net.modules:
        for index, mod in enumerate(net.modules):
            out.append(f'  subgraph "cluster_{mod}" {{')
            out.append(f'    label=<<B>{html.escape(mod.upper())}</B>>; style="rounded,dashed"; color="#B4B2A9"; '
                       f'fontsize=12; fontcolor="#5F5E5A";')
            for place in places.values():
                if place.module == mod:
                    out.append("  " + place_node(place))
            for transition in transitions:
                if transition.module == mod:
                    out.append("  " + transition_node(transition))
            for key, (name, owner) in templates.items():
                if owner == mod:
                    out.append("  " + template_node(key, name))
            out.append("  }")
        rest_places = [p for p in places.values() if not p.module]
        rest_transitions = [t for t in transitions if not t.module]
        rest_templates = [(k, n) for k, (n, o) in templates.items() if not o]
    else:
        rest_places = list(places.values())
        rest_transitions = transitions
        rest_templates = [(k, n) for k, (n, _o) in templates.items()]
    out += [place_node(p) for p in rest_places]
    out += [transition_node(t) for t in rest_transitions]
    out += [template_node(k, n) for k, n in rest_templates]

    for index, edge in enumerate(edges):
        attributes = [f'id="e{index}"']
        if edge.label:
            attributes.append(f'label=" {html.escape(edge.label)} "')
        if edge.kind == "inhibit":
            attributes += ['arrowhead=odot', 'style=dashed', 'color="#A32D2D"', 'arrowsize=0.9']
        place = edge.source if edge.kind != "out" else edge.target
        place_kind = places[place[2:]].kind if place.startswith("p:") else "template"
        if edge.kind == "in" and place_kind == "command":
            attributes.append('color="#185FA5"')
        if edge.kind != "inhibit" and place_kind == "resource":
            # machine-ready loops: lighter, so the workpiece flow stands out
            attributes += ['color="#97C459"', 'penwidth=0.8', 'arrowsize=0.5']
        out.append(f'  "{edge.source}" -> "{edge.target}" [{", ".join(attributes)}];')

    if legend:
        out.append(legend_source())
    out.append("}")
    return "\n".join(out)


def legend_source() -> str:
    rows = []
    for kind, style in KIND_STYLE.items():
        rows.append(f'<TR><TD><FONT COLOR="{style["line"]}" POINT-SIZE="16">●</FONT></TD>'
                    f'<TD ALIGN="LEFT">place: {html.escape(style["label"])}</TD></TR>')
    rows += [
        '<TR><TD><FONT POINT-SIZE="14">◌</FONT></TD><TD ALIGN="LEFT">template place, e.g. ${start}: chosen by the request</TD></TR>',
        '<TR><TD>■</TD><TD ALIGN="LEFT">transition: duration, [guard], ↩ result handed back to the request</TD></TR>',
        '<TR><TD>→</TD><TD ALIGN="LEFT">arc, labelled with the token it binds (wp = workpiece, job = request)</TD></TR>',
        '<TR><TD><FONT COLOR="#97C459">→</FONT></TD><TD ALIGN="LEFT">machine taken and given back (resource arc)</TD></TR>',
        '<TR><TD><FONT COLOR="#A32D2D">--o</FONT></TD><TD ALIGN="LEFT">inhibitor arc: fires only while the place is empty</TD></TR>',
        '<TR><TD>●</TD><TD ALIGN="LEFT">initial token · ≤ n capacity</TD></TR>',
    ]
    return ('  legend [shape=plaintext, fontsize=10, label=<<TABLE BORDER="1" COLOR="#D3D1C7" CELLBORDER="0" '
            'CELLPADDING="3" CELLSPACING="0"><TR><TD COLSPAN="2" ALIGN="LEFT"><B>Legend</B></TD></TR>'
            + "".join(rows) + "</TABLE>>];")


# ---------------------------------------------------------------- rendering

def run_dot(source: str, fmt: str, extra: list[str] | None = None) -> bytes:
    return subprocess.run(["dot", f"-T{fmt}", *(extra or [])], input=source.encode("utf-8"),
                          capture_output=True, check=True).stdout


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def main(argv: list[str]) -> int:
    files = [Path(a) for a in argv] or sorted(LAYOUTS.glob("*.yaml")) + sorted(LAYOUTS.glob("*.yml"))
    DOCS.mkdir(parents=True, exist_ok=True)
    index = ["# Petri nets of the layouts", "",
             "Generated by `python3 scripts/render-petri-nets.py` from the layout YAML files; do not edit by hand.",
             "How the YAML maps to the net: [yaml-to-petri-net.md](../../layouts/yaml-to-petri-net.md).", ""]
    for path in files:
        net = load(path)
        print(f"{path.name}: {len(net.places)} places, {len(net.transitions)} transitions, modules {net.modules}")
        views = [(None, net.id)] + [(m, f"{net.id}-{slug(m)}") for m in net.modules]
        index += [f"## {net.name}", "",
                  f"{len(net.places)} places, {len(net.transitions)} transitions, "
                  f"{len(net.commands)} API commands. Source: `{path.relative_to(ROOT) if path.is_relative_to(ROOT) else path}`", ""]
        for module, name in views:
            source = dot_source(net, module, legend=True)
            (DOCS / f"{name}.svg").write_bytes(run_dot(source, "svg"))
            (DOCS / f"{name}.png").write_bytes(run_dot(source, "png", ["-Gdpi=170"]))
            caption = "Whole net" if module is None else f"Module `{module}`"
            index += [f"### {caption}", "", f"![{caption}]({name}.svg)", "",
                      f"[SVG]({name}.svg) · [PNG for slides]({name}.png)", ""]
    (DOCS / "README.md").write_text("\n".join(index), encoding="utf-8")
    print(f"wrote {DOCS.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
