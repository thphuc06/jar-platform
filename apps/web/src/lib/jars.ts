const BY_CODE: Record<string, string> = {
  NEC: "hsl(var(--jar-necessities))",
  FFA: "hsl(var(--jar-investment))",
  LTSS: "hsl(var(--jar-savings))",
  EDU: "hsl(var(--jar-education))",
  PLAY: "hsl(var(--jar-play))",
  GIVE: "hsl(var(--jar-giving))",
};

/** Colours for custom jars (code CUSTOM), picked by position. */
const FALLBACK = [
  "hsl(30, 70%, 50%)",
  "hsl(290, 50%, 55%)",
  "hsl(170, 60%, 40%)",
  "hsl(10, 70%, 55%)",
  "hsl(215, 80%, 48%)",
  "hsl(40, 85%, 52%)",
];

/** The colour of a jar: fixed for the six standard jars, rotating for custom ones. */
export function jarColor(jar: { code: string }, index = 0): string {
  return BY_CODE[jar.code] ?? FALLBACK[index % FALLBACK.length];
}

/** Colours for slices of a chart that has no jar behind it (spending by category). */
export const CHART_COLORS = [
  "hsl(215, 80%, 48%)",
  "hsl(160, 55%, 38%)",
  "hsl(270, 55%, 50%)",
  "hsl(40, 85%, 52%)",
  "hsl(340, 68%, 50%)",
  "hsl(190, 65%, 42%)",
  "hsl(30, 70%, 50%)",
  "hsl(10, 70%, 55%)",
];
