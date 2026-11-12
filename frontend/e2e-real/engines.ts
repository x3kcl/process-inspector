// Direct engine REST for fixture seeding only (TEST-STRATEGY §10: seed over REST, never ACT_*
// rows). The UI under test never sees these calls — it reaches the engines through the BFF.
import { expect } from '@playwright/test'

const CRED = `Basic ${btoa('rest-admin:test')}`

export const ENGINES = {
  'engine-a': `http://localhost:${process.env.PI_ENGINE_A_PORT ?? '8081'}/flowable-rest/service`,
  'engine-b': `http://localhost:${process.env.PI_ENGINE_B_PORT ?? '8082'}/flowable-rest/service`,
} as const

export type EngineId = keyof typeof ENGINES

async function call(engine: EngineId, path: string, init: RequestInit = {}): Promise<unknown> {
  const response = await fetch(`${ENGINES[engine]}${path}`, {
    ...init,
    headers: { Authorization: CRED, 'Content-Type': 'application/json' },
  })
  if (!response.ok) {
    throw new Error(`${init.method ?? 'GET'} ${engine}${path} → ${String(response.status)}`)
  }
  return response.status === 204 ? null : response.json()
}

/** Fails loudly (with the fix) when seed.sh has not deployed the fixture definitions. */
export async function requireDefinition(engine: EngineId, key: string): Promise<void> {
  const body = (await call(engine, `/repository/process-definitions?key=${key}&latest=true`)) as {
    total: number
  }
  if (body.total === 0) {
    throw new Error(`${engine} lacks '${key}' — run docker/seed.sh --profile flowable-6 first`)
  }
}

export async function startInstance(
  engine: EngineId,
  processDefinitionKey: string,
  businessKey: string,
  variables: { name: string; type: string; value: unknown }[] = [],
): Promise<string> {
  const body = (await call(engine, '/runtime/process-instances', {
    method: 'POST',
    body: JSON.stringify({ processDefinitionKey, businessKey, variables }),
  })) as { id: string }
  return body.id
}

/** Bounded wait until the instance's async job has dead-lettered (R1/PT1S fixtures). */
export async function awaitDeadLetter(engine: EngineId, processInstanceId: string): Promise<void> {
  await expect
    .poll(
      async () => {
        const body = (await call(
          engine,
          `/management/deadletter-jobs?processInstanceId=${processInstanceId}`,
        )) as { total: number }
        return body.total
      },
      {
        timeout: 90_000,
        intervals: [1_000],
        message: `${engine}:${processInstanceId} never DLQ'd`,
      },
    )
    .toBeGreaterThan(0)
}
