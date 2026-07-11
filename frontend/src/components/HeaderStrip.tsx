import type { EngineDto } from '../api/model'
import { useEngines } from '../api/useEngines'
import { isInactiveLifecycle, lifecycleGloss } from '../lib/enginePolicy'
import { formatCount, formatSeconds } from '../lib/format'
import { glossTechnicalMessage } from '../lib/plainFailure'
import { EnvBadge } from './EnvBadge'

const LANE_TITLES: Record<string, string> = {
  exec: 'executable async jobs',
  timer: 'timer jobs',
  susp: 'suspended jobs',
  DLQ: 'dead-letter jobs',
}

// Client-side mirror of the default alarm thresholds (SPEC §4: >5 min warn) — display
// emphasis only; the authoritative per-engine thresholds live in the BFF config.
const OLDEST_EXEC_WARN_S = 300

/**
 * M1 Stage 0/1 global header: one card per engine off the shared 30s engines poll.
 * A dead engine renders as its own warning card — it never blanks the healthy ones.
 */
export function HeaderStrip() {
  const { data, error, isPending } = useEngines()
  return (
    <div className="engine-strip" role="group" aria-label="Engine health">
      {isPending && <span className="strip-note">probing engines…</span>}
      {!isPending && error !== null && (
        <span className="strip-note strip-error" role="alert">
          engine status unavailable — {error.message}
        </span>
      )}
      {data?.map((engine, index) => (
        <EngineCard key={engine.id ?? String(index)} engine={engine} />
      ))}
    </div>
  )
}

function EngineCard({ engine }: { engine: EngineDto }) {
  const reachable = engine.reachable === true
  const lanes = engine.jobLanes
  const oldestAge = engine.oldestExecutableJobAge
  const overdue = engine.overdueTimers
  // W1#4 (theme T6, R-SEM-17): a non-active engine renders as a GREYED card with the
  // lifecycle reason — never silently omitted, and never as a spurious "UNREACHABLE"
  // (a disabled engine is deliberately not probed; that is policy, not an outage).
  const inactive = isInactiveLifecycle(engine.lifecycle)
  if (inactive) {
    return (
      <div className="engine-card engine-card-disabled">
        <EnvBadge
          environment={engine.environment}
          accentColor={engine.accentColor}
          mode={engine.mode}
          lifecycle={engine.lifecycle}
        />
        <span className="engine-name">{engine.name ?? engine.id ?? 'unnamed engine'}</span>
        <span className="engine-disabled-note">
          {engine.lifecycle !== undefined ? lifecycleGloss(engine.lifecycle) : 'not active'}
        </span>
      </div>
    )
  }
  return (
    <div className={`engine-card${reachable ? '' : ' engine-card-down'}`}>
      <EnvBadge
        environment={engine.environment}
        accentColor={engine.accentColor}
        mode={engine.mode}
        lifecycle={engine.lifecycle}
      />
      <span className="engine-name">{engine.name ?? engine.id ?? 'unnamed engine'}</span>
      <span className="engine-version">
        {typeof engine.engineVersion === 'string' ? `v${engine.engineVersion}` : 'v?'}
      </span>
      {/* The BFF serializes just-recovered engines with jobLanes: null for one probe cycle
          (Jackson-null vs TS-undefined) — an undefined-only guard crashed the whole SPA
          on the unreachable→healthy transition. The generated type omits null, so the
          runtime guard reads as "unnecessary" to ESLint even though it is load-bearing. */}
      {reachable &&
        // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition -- runtime sends null despite the type
        (lanes !== undefined && lanes !== null ? (
          <span className="lanes">
            <Lane label="exec" value={lanes.executable} />
            <Lane label="timer" value={lanes.timer} />
            <Lane label="susp" value={lanes.suspended} />
            <Lane label="DLQ" value={lanes.deadletter} alarm={(lanes.deadletter ?? 0) > 0} />
            {/* W2 #7 (T9): unit token — the lane numbers count JOBS; the triage tiles
                below count INSTANCES. Never let the two read as the same family. */}
            <span className="count-unit" title="lane numbers are JOB counts, not instance counts">
              jobs
            </span>
          </span>
        ) : (
          <span className="strip-note">lanes unknown</span>
        ))}
      {!reachable && (
        // Usability round 1, Theme F: the raw exception text stays available in the
        // title — the visible line is a plain-language gloss, never a stack trace.
        <span className="engine-unreachable" title={engine.healthError}>
          ⚠ UNREACHABLE — {glossTechnicalMessage(engine.healthError)}
        </span>
      )}
      {reachable && oldestAge !== undefined && oldestAge > 0 && (
        <span className={`engine-alarm${oldestAge > OLDEST_EXEC_WARN_S ? ' alarm-warn' : ''}`}>
          oldest exec {formatSeconds(oldestAge)}
        </span>
      )}
      {reachable && overdue !== undefined && overdue > 0 && (
        <span className="engine-alarm alarm-warn">
          ⚠ {formatCount(overdue)} overdue timer{overdue === 1 ? '' : 's'}
        </span>
      )}
    </div>
  )
}

function Lane({
  label,
  value,
  alarm,
}: {
  label: string
  value: number | undefined
  alarm?: boolean
}) {
  return (
    <span className={`lane${alarm === true ? ' lane-alarm' : ''}`} title={LANE_TITLES[label]}>
      {label} {formatCount(value ?? 0)}
    </span>
  )
}
