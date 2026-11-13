"""Tiny layout helper for the AWS diagrams: grid placement, nested boxes and right-angle arrows.

Why not `splines=ortho`: Graphviz' orthogonal router ignores ports and edge labels and happily runs arrows over icon
labels. Here every node sits on a grid, every group box is computed from its members, and every arrow is a list of
horizontal and vertical segments whose lanes are chosen by the diagram author (so they run in the gaps between boxes).
Graphviz is still the renderer: this module writes a DOT file with pinned positions and explicit edge splines and runs
`neato -n2` on it, so fonts, icons and PNG output are the same as before. Icons come from the `diagrams` package.

After placing everything the module checks the result and prints a warning for each arrow that crosses an icon, a
label or another arrow's segment, so a layout mistake shows up when the script runs, not when somebody opens the PNG.

Coordinates: points, y grows downwards. `col`/`row` arguments are grid units converted with the canvas pitch.
"""
from __future__ import annotations

import html
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

import diagrams

ICON_ROOT = Path(diagrams.__file__).resolve().parent.parent

ARROW = 9.0  # arrow head length in points (arrowsize 0.9 * 10)


def icon_path(cls) -> str:
    return (ICON_ROOT / cls._icon_dir / cls._icon).as_posix()


# group styles, after the colours of AWS reference architectures
GROUP_STYLES = {
    "region": dict(color="#147EBA", fill="#F5FAFD", dashed=True),
    "vpc": dict(color="#8C4FFF", fill="#FBF9FF", dashed=False),
    "public": dict(color="#7AA116", fill="#F3F8E9", dashed=False),
    "private": dict(color="#147EBA", fill="#E8F3FA", dashed=False),
    "plain": dict(color="#5A6B86", fill="#FFFFFF", dashed=True),
    "aws": dict(color="#232F3E", fill="#FFFFFF", dashed=False),
    "github": dict(color="#5A6B86", fill="#F6F8FA", dashed=False),
    "sg": dict(color="#DD344C", fill="#FFFFFF", dashed=True),
}

EDGE_COLORS = {"default": "#4A5568", "purple": "#7B2FBE", "red": "#C0392B"}


@dataclass
class Node:
    id: str
    cls: type | None
    label: str
    x: float
    y: float  # icon centre
    w: float = 0.0
    lines: int = 1
    size: float = 64.0
    label_top: bool = False

    @property
    def label_h(self) -> float:
        return self.lines * LINE_H if self.label else 0.0

    @property
    def rects(self):
        """Icon rectangle and label rectangle (what an arrow must not touch)."""
        s = self.size / 2
        out = [(self.x - s, self.y - s, self.x + s, self.y + s)]
        if self.label:
            hw = self.w / 2
            if self.label_top:
                bottom = self.y - s - LABEL_GAP
                out.append((self.x - hw, bottom - self.label_h, self.x + hw, bottom))
            else:
                top = self.y + s + LABEL_GAP
                out.append((self.x - hw, top, self.x + hw, top + self.label_h))
        return out

    @property
    def box(self):
        """Rectangle (x0, y0, x1, y1) covering icon and label."""
        rs = self.rects
        return (min(r[0] for r in rs), min(r[1] for r in rs), max(r[2] for r in rs), max(r[3] for r in rs))


@dataclass
class Group:
    id: str
    label: str
    members: list
    kind: str
    pad: tuple
    include: list = field(default_factory=list)
    label_bottom: bool = False
    parent: str | None = None
    rect: tuple = (0, 0, 0, 0)
    label_rect: tuple = (0, 0, 0, 0)


@dataclass
class Edge:
    src: str
    dst: str
    sides: str
    lanes: list
    off: tuple
    label: str
    style: str
    color: str
    lseg: int | None
    lt: float | None
    lside: str | None
    ldx: float
    ldy: float
    pts: list = field(default_factory=list)
    label_rect: tuple | None = None
    label_xy: tuple | None = None


FONT = "Sans-Serif"
FS = 13.5
LINE_H = 17.0
LABEL_GAP = 3.0
CHAR_W = 0.56  # average glyph width / font size, a little generous on purpose


def text_w(s: str, fs: float = FS) -> float:
    return max(len(line) for line in s.split("\n")) * fs * CHAR_W


class Canvas:
    def __init__(self, title: str, out: Path, cw: float, rh: float, x0: float = 0, y0: float = 0,
                 icon: float = 64.0, dpi: int = 100):
        self.title = title
        self.out = Path(str(out) + ".png")
        self.cw, self.rh, self.x0, self.y0 = cw, rh, x0, y0
        self.icon = icon
        self.dpi = dpi
        self.nodes: dict[str, Node] = {}
        self.groups: dict[str, Group] = {}
        self.edges: list[Edge] = []
        self.texts: list[tuple] = []  # (x, y, label, html, w, h)

    # ---- placement -------------------------------------------------------------------------------------------
    def X(self, c) -> float:
        if isinstance(c, tuple):
            return self.x0 + c[0] * self.cw + c[1]
        return self.x0 + c * self.cw

    def Y(self, r) -> float:
        if isinstance(r, tuple):
            return self.y0 + r[0] * self.rh + r[1]
        return self.y0 + r * self.rh

    def node(self, id: str, cls, label: str, col, row, size: float | None = None, label_top: bool = False) -> str:
        """Icon centred on the grid point; the label sits below it, or above it when label_top (then the bottom
        of the icon is free for arrows)."""
        n = Node(id, cls, label, self.X(col), self.Y(row), text_w(label) if label else 0,
                 label.count("\n") + 1 if label else 0, size or self.icon, label_top)
        self.nodes[id] = n
        return id

    def group(self, id: str, label: str, members: list, kind: str = "plain", pad=(16, 16, 16, 14),
              include: list | None = None, label_bottom: bool = False) -> str:
        """pad = (left, top-below-label, right, bottom); include = extra rectangles (col0, row0, col1, row1) in grid
        units that the box must cover (to align widths of sibling boxes); label_bottom puts the label at the bottom
        so that arrows may enter through the top."""
        self.groups[id] = Group(id, label, list(members), kind, pad, list(include or []), label_bottom)
        return id

    def edge(self, src: str, dst: str, sides: str, lanes=None, off=(0, 0), label: str = "", style: str = "solid",
             color: str = "default", lseg: int | None = None, lt: float | None = None, lside: str | None = None,
             ldx: float = 0, ldy: float = 0):
        self.edges.append(Edge(src, dst, sides, list(lanes or []), off, label, style, color, lseg, lt, lside, ldx, ldy))

    def note(self, x, y, text: str):
        """Free text (centre x, y in points or grid tuples)."""
        w = text_w(text)
        h = (text.count("\n") + 1) * LINE_H
        self.texts.append((self.X(x), self.Y(y), text, False, w, h))

    # ---- geometry ------------------------------------------------------------------------------------------
    def _anchor(self, nid: str, side: str, off: float):
        """Where an arrow touches a node (icon edge; below the label for a bottom exit) or a group (border centre)."""
        if nid in self.groups:
            x0, y0, x1, y1 = self.groups[nid].rect
            cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
            if isinstance(off, tuple):  # ("row", r) / ("col", c): absolute grid position along the border
                cy, cx, off = (self.Y(off[1]), self.X(off[1]), 0)
            return {"l": (x0, cy + off), "r": (x1, cy + off), "t": (cx + off, y0), "b": (cx + off, y1)}[side]
        n = self.nodes[nid]
        s = n.size / 2
        box = n.box
        if side == "l":
            return (n.x - s, n.y + off)
        if side == "r":
            return (n.x + s, n.y + off)
        if side == "t":
            return (n.x + off, (box[1] - 2) if n.label_top else n.y - s)
        if side == "b":
            return (n.x + off, n.y + s if n.label_top else box[3] + 2)
        raise ValueError(side)

    def _route(self, e: Edge):
        p0 = self._anchor(e.src, e.sides[0], 0 if e.off[0] == "align" else e.off[0])
        p1 = self._anchor(e.dst, e.sides[1], 0 if e.off[1] == "align" else e.off[1])
        # an end at a group box may ask to be aligned with the other end ("align") so the arrow stays straight
        if e.dst in self.groups and e.off[1] == "align":
            p1 = self._anchor(e.dst, e.sides[1], 0)
            p1 = (p1[0], p0[1]) if e.sides[1] in "lr" else (p0[0], p1[1])
        if e.src in self.groups and e.off[0] == "align":
            p0 = self._anchor(e.src, e.sides[0], 0)
            p0 = (p0[0], p1[1]) if e.sides[0] in "lr" else (p1[0], p0[1])
        horiz = e.sides[0] in "lr"
        end_h = e.sides[1] in "lr"
        pts = [p0]
        cur = p0
        lanes = list(e.lanes)
        # number of segments needed
        if horiz == end_h:
            n_lanes_needed = 1  # Z shape
        else:
            n_lanes_needed = 0  # L shape
        if not lanes and n_lanes_needed:
            lanes = [None]
        h = horiz
        for lane in lanes:
            if lane is None:  # default: halfway
                lane = (p0[0] + p1[0]) / 2 if h else (p0[1] + p1[1]) / 2
            else:
                lane = self.X(lane) if h else self.Y(lane)
            cur = (lane, cur[1]) if h else (cur[0], lane)
            pts.append(cur)
            h = not h
        # now current segment orientation is `h` (moving along h), final segment must be end_h
        if h == end_h:
            # the segment at the end moves along its orientation; its fixed coordinate is p1's
            if h:
                pts.append((cur[0], p1[1]))
                pts.append(p1)
            else:
                pts.append((p1[0], cur[1]))
                pts.append(p1)
        else:
            if h:  # moving horizontally to p1.x then vertical to p1
                pts.append((p1[0], cur[1]))
            else:
                pts.append((cur[0], p1[1]))
            pts.append(p1)
        # tidy: drop duplicates and collinear points
        out = []
        for p in pts:
            if out and abs(out[-1][0] - p[0]) < 0.01 and abs(out[-1][1] - p[1]) < 0.01:
                continue
            out.append(p)
        changed = True
        while changed:
            changed = False
            for i in range(1, len(out) - 1):
                (x0, y0), (x1, y1), (x2, y2) = out[i - 1], out[i], out[i + 1]
                if (abs(x0 - x1) < 0.01 and abs(x1 - x2) < 0.01) or (abs(y0 - y1) < 0.01 and abs(y1 - y2) < 0.01):
                    del out[i]
                    changed = True
                    break
        e.pts = out

    def _label_conflicts(self, r, own: Edge) -> int:
        """Number of things the rectangle r would collide with."""
        n = 0
        for node in self.nodes.values():
            for rect in node.rects:
                if self._seg_hits(((r[0], r[1]), (r[2], r[3])), rect, 0):
                    n += 1
        for g in self.groups.values():
            gx0, gy0, gx1, gy1 = g.rect
            m = 5.0  # keep clear of the border line
            if gy1 > r[1] - m and gy0 < r[3] + m:
                if r[0] - m < gx0 < r[2] + m or r[0] - m < gx1 < r[2] + m:
                    n += 1
            if gx1 > r[0] - m and gx0 < r[2] + m:
                if r[1] - m < gy0 < r[3] + m or r[1] - m < gy1 < r[3] + m:
                    n += 1
            if g.label and self._seg_hits(((r[0], r[1]), (r[2], r[3])), g.label_rect, 0):
                n += 1
        for o in self.edges:
            if o.label_rect and o is not own and self._seg_hits(((r[0], r[1]), (r[2], r[3])), o.label_rect, 0):
                n += 1
            for seg in zip(o.pts, o.pts[1:]):
                if self._seg_hits(seg, r, 0):
                    n += 1
        return n

    def _place_label(self, e: Edge):
        if not e.label:
            return
        segs = list(zip(e.pts, e.pts[1:]))
        w, h = text_w(e.label, FS - 1) + 6, (e.label.count("\n") + 1) * (LINE_H - 1.5) + 2
        seg_ids = [e.lseg] if e.lseg is not None else sorted(
            range(len(segs)), key=lambda i: -(abs(segs[i][0][0] - segs[i][1][0]) + abs(segs[i][0][1] - segs[i][1][1])))
        fixed = e.lt is not None
        ts = [e.lt] if fixed else [0.5 + sgn * 0.04 * k for k in range(0, 11) for sgn in ((1, -1) if k else (1,))]
        best = None
        for si in seg_ids:
            (x0, y0), (x1, y1) = segs[si]
            horiz = abs(y0 - y1) < 0.01
            sides = [e.lside] if e.lside else (["a", "b"] if horiz else ["r", "l"])
            for t in ts:
                for side in sides:
                    if horiz:
                        cx = x0 + (x1 - x0) * t
                        cy = y0 - 3 - h / 2 if side == "a" else y0 + 3 + h / 2
                    else:
                        cy = y0 + (y1 - y0) * t
                        cx = x0 + 4 + w / 2 if side == "r" else x0 - 4 - w / 2
                    cx += e.ldx
                    cy += e.ldy
                    r = (cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
                    c = self._label_conflicts(r, e)
                    if best is None or c < best[0]:
                        best = (c, cx, cy, r)
                    if c == 0:
                        break
                if best and best[0] == 0:
                    break
            if best and best[0] == 0:
                break
        _, cx, cy, r = best
        e.label_xy = (cx, cy)
        e.label_rect = r

    def _layout_groups(self):
        # inner groups first
        order = []

        def visit(gid):
            if gid in order:
                return
            for m in self.groups[gid].members:
                if m in self.groups:
                    self.groups[m].parent = gid
                    visit(m)
            order.append(gid)

        for gid in self.groups:
            visit(gid)
        for gid in order:
            g = self.groups[gid]
            rects = []
            for m in g.members:
                if m in self.nodes:
                    rects.append(self.nodes[m].box)
                else:
                    rects.append(self.groups[m].rect)
            for (c0, r0, c1, r1) in g.include:
                rects.append((self.X(c0), self.Y(r0), self.X(c1), self.Y(r1)))
            x0 = min(r[0] for r in rects)
            y0 = min(r[1] for r in rects)
            x1 = max(r[2] for r in rects)
            y1 = max(r[3] for r in rects)
            lines = g.label.count("\n") + 1 if g.label else 0
            lh = lines * LINE_H + 6 if g.label else 0
            pl, pt, pr, pb = g.pad
            w = text_w(g.label, FS) * 1.08 + 6 if g.label else 0  # bold first line is a bit wider
            x1 = max(x1, x0 - pl + w + 16 - pr)  # the box is at least as wide as its label
            if g.label_bottom:
                g.rect = (x0 - pl, y0 - pt, x1 + pr, y1 + pb + lh)
                g.label_rect = (g.rect[0] + 8, g.rect[3] - 5 - lines * LINE_H, g.rect[0] + 8 + w,
                                g.rect[3] - 5) if g.label else (0, 0, 0, 0)
            else:
                g.rect = (x0 - pl, y0 - pt - lh, x1 + pr, y1 + pb)
                g.label_rect = (g.rect[0] + 8, g.rect[1] + 5, g.rect[0] + 8 + w,
                                g.rect[1] + 5 + lines * LINE_H) if g.label else (0, 0, 0, 0)
        self._group_order = order

    # ---- checks --------------------------------------------------------------------------------------------
    @staticmethod
    def _seg_hits(seg, rect, shrink=0.0):
        (x0, y0), (x1, y1) = seg
        rx0, ry0, rx1, ry1 = rect[0] + shrink, rect[1] + shrink, rect[2] - shrink, rect[3] - shrink
        sx0, sx1 = sorted((x0, x1))
        sy0, sy1 = sorted((y0, y1))
        return sx1 > rx0 and sx0 < rx1 and sy1 > ry0 and sy0 < ry1

    def _lint(self):
        warns = []
        for i, e in enumerate(self.edges):
            name = f"{e.src}->{e.dst}"
            segs = list(zip(e.pts, e.pts[1:]))
            for k, seg in enumerate(segs):
                (x0, y0), (x1, y1) = seg
                # shorten at the ends that touch the own nodes so that the anchor does not count as a hit
                for nid, n in self.nodes.items():
                    own_start = nid == e.src and k == 0
                    own_end = nid == e.dst and k == len(segs) - 1
                    for ri, rect in enumerate(n.rects):
                        s_ = seg
                        if (own_start or own_end) and ri == 0:
                            # the anchor sits on the icon border: ignore the first/last 3pt
                            trim = 3.0
                            horiz = abs(y0 - y1) < 0.01
                            if own_start:
                                if horiz:
                                    s_ = ((x0 + (trim if x1 > x0 else -trim), y0), (x1, y1))
                                else:
                                    s_ = ((x0, y0 + (trim if y1 > y0 else -trim)), (x1, y1))
                            if own_end:
                                (a, b) = s_
                                if horiz:
                                    s_ = (a, (b[0] - (trim if x1 > x0 else -trim), b[1]))
                                else:
                                    s_ = (a, (b[0], b[1] - (trim if y1 > y0 else -trim)))
                        if self._seg_hits(s_, rect, 0.5):
                            warns.append(f"{name}: segment {k} crosses {'label' if ri else 'icon'} of {nid}")
                for gid, g in self.groups.items():
                    if g.label and self._seg_hits(seg, g.label_rect, 0.5):
                        warns.append(f"{name}: segment {k} crosses label of group {gid}")
                for j, o in enumerate(self.edges):
                    if o.label_rect and self._seg_hits(seg, o.label_rect, 0.5):
                        warns.append(f"{name}: segment {k} runs over the label of {o.src}->{o.dst}")
            last = segs[-1]
            ln = abs(last[0][0] - last[1][0]) + abs(last[0][1] - last[1][1])
            if ln < ARROW + 6:
                warns.append(f"{name}: last segment only {ln:.0f}pt long, arrow head cramped")
            # overlap with other edges
            for j, o in enumerate(self.edges):
                if j <= i:
                    continue
                for s2 in zip(o.pts, o.pts[1:]):
                    for s1 in segs:
                        if self._collinear_overlap(s1, s2):
                            warns.append(f"{name} and {o.src}->{o.dst} overlap on a shared line")
            if e.label_rect:
                lr = e.label_rect
                for nid, n in self.nodes.items():
                    for rect in n.rects:
                        if self._seg_hits(((lr[0], lr[1]), (lr[2], lr[3])), rect, 0):
                            warns.append(f"label '{e.label}' overlaps node {nid}")
                for gid, g in self.groups.items():
                    if g.label and self._seg_hits(((lr[0], lr[1]), (lr[2], lr[3])), g.label_rect, 0):
                        warns.append(f"label '{e.label}' overlaps label of group {gid}")
        return sorted(set(warns))

    @staticmethod
    def _collinear_overlap(s1, s2):
        (a0, a1), (b0, b1) = s1, s2
        if abs(a0[1] - a1[1]) < 0.01 and abs(b0[1] - b1[1]) < 0.01 and abs(a0[1] - b0[1]) < 3:
            lo = max(min(a0[0], a1[0]), min(b0[0], b1[0]))
            hi = min(max(a0[0], a1[0]), max(b0[0], b1[0]))
            return hi - lo > 2
        if abs(a0[0] - a1[0]) < 0.01 and abs(b0[0] - b1[0]) < 0.01 and abs(a0[0] - b0[0]) < 3:
            lo = max(min(a0[1], a1[1]), min(b0[1], b1[1]))
            hi = min(max(a0[1], a1[1]), max(b0[1], b1[1]))
            return hi - lo > 2
        return False

    # ---- output --------------------------------------------------------------------------------------------
    def render(self) -> None:
        self._layout_groups()
        for e in self.edges:
            self._route(e)
        for e in self.edges:
            self._place_label(e)

        # canvas bounds
        xs0, ys0, xs1, ys1 = [], [], [], []
        for n in self.nodes.values():
            b = n.box
            xs0.append(b[0]); ys0.append(b[1]); xs1.append(b[2]); ys1.append(b[3])
        for g in self.groups.values():
            xs0.append(g.rect[0]); ys0.append(g.rect[1]); xs1.append(g.rect[2]); ys1.append(g.rect[3])
        for e in self.edges:
            for p in e.pts:
                xs0.append(p[0]); ys0.append(p[1]); xs1.append(p[0]); ys1.append(p[1])
            if e.label_rect:
                xs0.append(e.label_rect[0]); ys0.append(e.label_rect[1])
                xs1.append(e.label_rect[2]); ys1.append(e.label_rect[3])
        title_h = 44
        margin = 24
        left, right = min(xs0) - margin, max(xs1) + margin
        top, bottom = min(ys0) - margin - title_h, max(ys1) + margin
        W, H = right - left, bottom - top

        def gx(x):
            return x - left

        def gy(y):
            return H - (y - top)  # graphviz: y up

        warns = self._lint()

        dot = []
        dot.append("digraph G {")
        dot.append(f'  graph [bb="0,0,{W:.1f},{H:.1f}", bgcolor="white", splines=true, '
                   f'fontname="{FONT}", pad=0, outputorder=edgesfirst];')
        dot.append(f'  "corner0" [shape=point, style=invis, pos="0,0!"];')
        dot.append(f'  "corner1" [shape=point, style=invis, pos="{W:.1f},{H:.1f}!"];')
        dot.append(f'  node [fontname="{FONT}", fontsize={FS}, fontcolor="#1F2937"];')
        dot.append(f'  edge [fontname="{FONT}", fontsize={FS - 1}, arrowsize=0.9, headclip=false, tailclip=false, '
                   f'penwidth=1.4];')

        # title
        tw = text_w(self.title, 18) + 20
        dot.append(f'  title [shape=plaintext, label=<<B>{html.escape(self.title)}</B>>, fontsize=18, '
                   f'fixedsize=true, width={tw / 72:.3f}, height={30 / 72:.3f}, '
                   f'pos="{gx(left + margin + tw / 2):.1f},{gy(top + margin + 12):.1f}!"];')

        # groups, nested in dot so the draw order is parent first
        children: dict[str | None, list] = {}
        for gid in self.groups:
            children.setdefault(self.groups[gid].parent, []).append(gid)

        def emit(gid, indent):
            g = self.groups[gid]
            st = GROUP_STYLES[g.kind]
            r = g.rect
            sp = "  " * indent
            dot.append(f'{sp}subgraph "cluster_{gid}" {{')
            style = "rounded,filled" + (",dashed" if st["dashed"] else "")
            dot.append(f'{sp}  graph [bb="{gx(r[0]):.1f},{gy(r[3]):.1f},{gx(r[2]):.1f},{gy(r[1]):.1f}", '
                       f'style="{style}", color="{st["color"]}", fillcolor="{st["fill"]}", penwidth=1.6, label=""];')
            for c in children.get(gid, []):
                emit(c, indent + 1)
            dot.append(f"{sp}}}")

        for gid in children.get(None, []):
            emit(gid, 1)

        # arrows that start or end at a group box need a (hidden) node of that name for neato
        for gid in {x for e in self.edges for x in (e.src, e.dst) if x in self.groups}:
            r = self.groups[gid].rect
            dot.append(f'  "{gid}" [shape=point, style=invis, pos="{gx((r[0] + r[2]) / 2):.1f},'
                       f'{gy((r[1] + r[3]) / 2):.1f}!"];')

        # group labels as text nodes
        for gid, g in self.groups.items():
            if not g.label:
                continue
            st = GROUP_STYLES[g.kind]
            lr = g.label_rect
            parts = g.label.split("\n")
            for k, part in enumerate(parts):
                # left-justified text nodes (line ends with backslash-l): first line bold, the rest regular
                y_mid = lr[1] + LINE_H * k + LINE_H / 2
                w = text_w(part, FS) + 8
                font = f'{FONT} Bold' if k == 0 else FONT
                esc = part.replace("\\", "\\\\").replace('"', '\\"')
                dot.append(f'  "gl_{gid}_{k}" [shape=plaintext, label="{esc}\\l", fontname="{font}", '
                           f'fontcolor="{st["color"]}", fixedsize=true, width={w / 72:.3f}, height={LINE_H / 72:.3f}, '
                           f'margin=0, pos="{gx(lr[0] + w / 2):.1f},{gy(y_mid):.1f}!"];')

        # nodes: the icon alone, the label as a separate text node (so that the label can be as wide as it likes)
        for n in self.nodes.values():
            if n.cls is None:
                continue
            dot.append(f'  "{n.id}" [shape=none, image="{icon_path(n.cls)}", imagescale=true, fixedsize=true, '
                       f'width={n.size / 72:.3f}, height={n.size / 72:.3f}, margin=0, label="", '
                       f'pos="{gx(n.x):.1f},{gy(n.y):.1f}!"];')
            if n.label:
                lbl = n.label.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
                cy = (n.y - n.size / 2 - LABEL_GAP - n.label_h / 2) if n.label_top else (n.y + n.size / 2 + LABEL_GAP + n.label_h / 2)
                dot.append(f'  "{n.id}__label" [shape=plaintext, label="{lbl}", fixedsize=true, '
                           f'width={(n.w + 10) / 72:.3f}, height={n.label_h / 72:.3f}, margin=0, '
                           f'pos="{gx(n.x):.1f},{gy(cy):.1f}!"];')

        # free notes
        for i, (x, y, text, _, w, h) in enumerate(self.texts):
            label = text.replace("\n", "\\n")
            dot.append(f'  "note{i}" [shape=plaintext, label="{label}", fixedsize=true, width={w / 72:.3f}, '
                       f'height={h / 72:.3f}, margin=0, pos="{gx(x):.1f},{gy(y):.1f}!"];')

        # edge labels as text nodes
        for i, e in enumerate(self.edges):
            if not e.label:
                continue
            r = e.label_rect
            label = e.label.replace("\n", "\\n")
            dot.append(f'  "el{i}" [shape=plaintext, label="{label}", fontsize={FS - 1}, fontcolor="#374151", '
                       f'fixedsize=true, width={(r[2] - r[0]) / 72:.3f}, height={(r[3] - r[1]) / 72:.3f}, margin=0, '
                       f'pos="{gx(e.label_xy[0]):.1f},{gy(e.label_xy[1]):.1f}!"];')

        # edges
        for e in self.edges:
            pts = e.pts[:]
            # pull the spline end back by the arrow length; the arrow tip stays on the anchor
            (px, py), (qx, qy) = pts[-2], pts[-1]
            dx, dy = qx - px, qy - py
            ln = (dx * dx + dy * dy) ** 0.5
            ux, uy = dx / ln, dy / ln
            tip = (qx, qy)
            pts[-1] = (qx - ux * ARROW, qy - uy * ARROW)
            # bezier chain: p0, (p0, p1, p1), (p1, p2, p2) ...
            chain = [pts[0]]
            for a, b in zip(pts, pts[1:]):
                chain += [a, b, b]
            spline = " ".join(f"{gx(x):.1f},{gy(y):.1f}" for x, y in chain)
            pos = f"e,{gx(tip[0]):.1f},{gy(tip[1]):.1f} {spline}"
            style = {"solid": "solid", "dashed": "dashed", "dotted": "dotted"}[e.style]
            color = EDGE_COLORS.get(e.color, e.color)
            dot.append(f'  "{e.src}" -> "{e.dst}" [pos="{pos}", style={style}, color="{color}", '
                       f'fillcolor="{color}"];')
        dot.append("}")

        text = "\n".join(dot)
        self.out.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "g.dot"
            src.write_text(text, encoding="utf-8")
            res = subprocess.run(["neato", "-n2", "-Tpng", str(src), "-o", str(self.out)], capture_output=True,
                                 text=True)
            if res.returncode != 0:
                raise SystemExit(res.stderr)
            for line in res.stderr.splitlines():
                if "too small for label" not in line:
                    print(line, file=sys.stderr)
        dbg = self.out.with_suffix(".dot.txt")
        if "--keep-dot" in sys.argv:
            dbg.write_text(text, encoding="utf-8")
        print(f"{self.out.name}: {W:.0f} x {H:.0f} pt, {len(self.nodes)} nodes, {len(self.edges)} arrows")
        for w in warns:
            print("  LAYOUT WARNING:", w)
