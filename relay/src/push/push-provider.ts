/**
 * Transport-agnostic push interface. FCM is the only implementation in F1;
 * UnifiedPush can be added later without touching the dispatcher.
 */
export interface PushTarget {
  deviceId: string;
  token: string;
}

/** String map delivered to the app (FCM `data`). See `PushData` in the OpenAPI. */
export type PushPayload = Record<string, string>;

export interface PushOptions {
  ttlSeconds: number;
  highPriority: boolean;
  collapseKey?: string;
}

export type PushResult = { ok: true } | { ok: false; unregistered: boolean; error: string };

export interface PushProvider {
  readonly name: string;
  send(target: PushTarget, payload: PushPayload, opts: PushOptions): Promise<PushResult>;
}
