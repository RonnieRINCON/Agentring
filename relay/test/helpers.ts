import { buildApp, type BuiltApp } from "../src/app.js";
import type { Config } from "../src/config.js";
import type { JoinInfo, LiveKitService } from "../src/livekit.js";
import type { PushOptions, PushPayload, PushProvider, PushResult, PushTarget } from "../src/push/push-provider.js";

export class FakePush implements PushProvider {
  readonly name = "fake";
  sent: Array<{ target: PushTarget; payload: PushPayload; opts: PushOptions }> = [];
  next: PushResult = { ok: true };
  async send(target: PushTarget, payload: PushPayload, opts: PushOptions): Promise<PushResult> {
    this.sent.push({ target, payload, opts });
    return this.next;
  }
}

/** Records room operations; webhooks are "signed" by passing authorization "ok". */
export class FakeLiveKit implements LiveKitService {
  created: string[] = [];
  deleted: string[] = [];
  async join(room: string, identity: string): Promise<JoinInfo> {
    return { livekit_url: "wss://lk.test", livekit_token: `tok:${room}:${identity}`, room, identity, expires_at: Date.now() + 120_000 };
  }
  async createRoom(room: string): Promise<void> {
    this.created.push(room);
  }
  async deleteRoom(room: string): Promise<void> {
    this.deleted.push(room);
  }
  async receiveWebhook(body: string, authorization: string | undefined) {
    if (authorization !== "ok") throw new Error("bad signature");
    return JSON.parse(body);
  }
}

export function testConfig(over: Partial<Config> = {}): Config {
  return {
    host: "127.0.0.1",
    port: 0,
    publicUrl: "https://relay.test",
    dataDir: ":memory:",
    agentName: "TestAgent",
    apiKey: "ak_test_key",
    ringTimeoutS: 30,
    insecureDev: false,
    fcm: {
      serviceAccountFile: "/dev/null",
      apiBase: "http://unused",
      client: { project_id: "p", application_id: "1:2:android:3", api_key: "k", sender_id: "2" },
    },
    ...over,
  };
}

export const AGENT = { authorization: "Bearer ak_test_key" };

export async function setup(over: Partial<Config> = {}): Promise<BuiltApp & { push: FakePush; livekit: FakeLiveKit }> {
  const push = new FakePush();
  const livekit = new FakeLiveKit();
  const built = await buildApp(testConfig(over), { logger: false, pushProvider: push, livekit });
  return { ...built, push, livekit };
}

/** Creates a pairing token and redeems it; returns the device credentials. */
export async function pairDevice(b: BuiltApp, name = "Pixel", fcm_token: string | undefined = "fcm-tok-1") {
  const t = await b.app.inject({ method: "POST", url: "/v1/pairing-tokens", headers: AGENT });
  const { pairing_token } = t.json();
  const r = await b.app.inject({
    method: "POST",
    url: "/v1/pair",
    payload: { pairing_token, device_name: name, platform: "android", ...(fcm_token ? { fcm_token } : {}) },
  });
  if (r.statusCode !== 201) throw new Error(`pair failed: ${r.body}`);
  const body = r.json();
  return { ...body, headers: { authorization: `Bearer ${body.device_jwt}` } } as {
    device_id: string;
    device_jwt: string;
    push_secret: string;
    relay_id: string;
    headers: { authorization: string };
  };
}
