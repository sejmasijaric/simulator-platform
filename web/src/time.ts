/** Parses an ISO-8601 duration such as "PT1M4.5S" into seconds. */
export function seconds(iso: string | null | undefined): number {
  if (!iso) return 0;
  const match = /^PT(?:(-?\d+)H)?(?:(-?\d+)M)?(?:(-?[\d.]+)S)?$/.exec(iso);
  if (!match) return 0;
  return Number(match[1] ?? 0) * 3600 + Number(match[2] ?? 0) * 60 + Number(match[3] ?? 0);
}

/** Simulated clock, m:ss.s */
export function clock(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60);
  const rest = totalSeconds - minutes * 60;
  return `${minutes}:${rest.toFixed(1).padStart(4, '0')}`;
}

/** A duration for people: "5 s", "2.4 s", "1 min 30 s". */
export function human(totalSeconds: number): string {
  if (totalSeconds < 60) return `${Number(totalSeconds.toFixed(1))} s`;
  const minutes = Math.floor(totalSeconds / 60);
  const rest = Math.round(totalSeconds - minutes * 60);
  return rest ? `${minutes} min ${rest} s` : `${minutes} min`;
}
