import { readFileSync } from "node:fs";
import { SignJWT, importPKCS8 } from "jose";
import type { PushOptions, PushPayload, PushProvider, PushResult, PushTarget } from "./push-provider.js";

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

/** FCM HTTP v1 provider authenticated with a service account (no firebase-admin dependency). */
export class FcmProvider implements PushProvider {
  readonly name = "fcm";
  private readonly sa: ServiceAccount;
  private accessToken?: { value: string; expiresAt: number };

  constructor(
    serviceAccountFile: string,
    private readonly apiBase = "https://fcm.googleapis.com",
    private readonly fetchImpl: typeof fetch = fetch,
  ) {
    this.sa = JSON.parse(readFileSync(serviceAccountFile, "utf8")) as ServiceAccount;
    for (const k of ["project_id", "client_email", "private_key"] as const) {
      if (!this.sa[k]) throw new Error(`FCM service account file is missing ${k}`);
    }
  }

  get projectId(): string {
    return this.sa.project_id;
  }

  async prepare(): Promise<void> {
    await this.token();
  }

  private async token(): Promise<string> {
    if (this.accessToken && this.accessToken.expiresAt > Date.now() + 60_000) return this.accessToken.value;
    const tokenUri = this.sa.token_uri ?? "https://oauth2.googleapis.com/token";
    const key = await importPKCS8(this.sa.private_key, "RS256");
    const now = Math.floor(Date.now() / 1000);
    const assertion = await new SignJWT({ scope: SCOPE })
      .setProtectedHeader({ alg: "RS256", typ: "JWT" })
      .setIssuer(this.sa.client_email)
      .setAudience(tokenUri)
      .setIssuedAt(now)
      .setExpirationTime(now + 3600)
      .sign(key);
    const res = await this.fetchImpl(tokenUri, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
    });
    if (!res.ok) throw new Error(`OAuth token exchange failed: ${res.status} ${await res.text()}`);
    const body = (await res.json()) as { access_token: string; expires_in: number };
    this.accessToken = { value: body.access_token, expiresAt: Date.now() + body.expires_in * 1000 };
    return body.access_token;
  }

  async send(target: PushTarget, payload: PushPayload, opts: PushOptions): Promise<PushResult> {
    let token: string;
    try {
      token = await this.token();
    } catch (e) {
      return { ok: false, unregistered: false, error: String(e) };
    }
    const message = {
      message: {
        token: target.token,
        data: payload,
        android: {
          priority: opts.highPriority ? "HIGH" : "NORMAL",
          ttl: `${opts.ttlSeconds}s`,
          ...(opts.collapseKey ? { collapse_key: opts.collapseKey } : {}),
        },
      },
    };
    const res = await this.fetchImpl(`${this.apiBase}/v1/projects/${this.sa.project_id}/messages:send`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify(message),
    });
    if (res.ok) return { ok: true };
    const text = await res.text();
    // FCM v1 reports stale tokens as 404 UNREGISTERED (or 400 INVALID_ARGUMENT on a malformed token).
    const unregistered = res.status === 404 || text.includes("UNREGISTERED");
    if (res.status === 401) this.accessToken = undefined;
    return { ok: false, unregistered, error: `${res.status} ${text.slice(0, 300)}` };
  }
}
