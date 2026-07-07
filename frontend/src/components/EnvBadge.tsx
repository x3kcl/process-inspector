const KNOWN_ENVS = new Set(['prod', 'test', 'dev'])

/** Usability round 1, Theme H3: a hover explanation of the stakes per environment band. */
const ENV_TITLES: Record<string, string> = {
  prod: 'PROD — a production engine. Real customers, real side effects.',
  test: 'TEST — a testing engine. Not customer-facing.',
  dev: 'DEV — a development engine. Low stakes.',
  unknown: 'This engine declared no environment — treat it as production.',
}

interface Props {
  environment: string | undefined
  accentColor?: string
}

/**
 * SPEC §10a: color never carries meaning alone. The band co-renders the literal
 * PROD/TEST/DEV token and each band has a DISTINCT border style (solid/dashed/dotted),
 * so the badges stay tellable-apart in grayscale. The freeform per-engine accentColor is
 * demoted to a small decorative dot — environment owns the semantics.
 */
export function EnvBadge({ environment, accentColor }: Props) {
  const env = environment !== undefined && KNOWN_ENVS.has(environment) ? environment : 'unknown'
  return (
    <span className={`env-badge env-${env}`} title={ENV_TITLES[env]}>
      {accentColor !== undefined && (
        <span className="accent-dot" style={{ background: accentColor }} aria-hidden="true" />
      )}
      {env === 'unknown' ? 'ENV?' : env.toUpperCase()}
    </span>
  )
}
