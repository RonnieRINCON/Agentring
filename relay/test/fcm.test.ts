import { generateKeyPairSync } from "node:crypto";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { decodeJwt } from "jose";
import { describe, expect, it } from "vitest";
import { FcmProvider } from "../src/push/fcm.js";

function serviceAccountFile(): string {
  const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const dir = mkdtempSync(join(tmpdir(), "fcm-"));
  const file = join(dir, "sa.json");
  writeFileSync(
    file,
    JSON.stringify({
      project_id: "my-proj",
      client_email: "relay@my-proj.iam.gserviceaccount.com",
      private_key: privateKey.export({ type: "pkcs8", format: "pem" }),
      token_uri: "https://oauth.test/token",
    }),
  );
  return file;
}

describe("FcmProvider", () => {
  it("exchanges a service-account JWT for a token and sends an HTTP v1 data message", async () => {
    const calls: Array<{ url: string; init: RequestInit }> = [];
    const fakeFetch = (async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      if (url === "https://oauth.test/token") {
        return new Response(JSON.stringify({ access_token: "ya29.x", expires_in: 3600 }), { status: 200 });
      }
      if (calls.filter((c) => c.url.includes("messages:send")).length === 2) {
        return new Response('{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}', { status: 404 });
      }
      return new Response("{}", { status: 200 });
    }) as unknown as typeof fetch;

    const p = new FcmProvider(serviceAccountFile(), "https://fcm.test", fakeFetch);
    const res = await p.send({ deviceId: "dev_1", token: "tok" }, { v: "1", event: "{}" }, { ttlSeconds: 30, highPriority: true });
    expect(res).toEqual({ ok: true });

    const tokenCall = calls[0]!;
    const form = new URLSearchParams(tokenCall.init.body as string);
    expect(form.get("grant_type")).toBe("urn:ietf:params:oauth:grant-type:jwt-bearer");
    const claims = decodeJwt(form.get("assertion")!);
    expect(claims).toMatchObject({
      iss: "relay@my-proj.iam.gserviceaccount.com",
      aud: "https://oauth.test/token",
      scope: "https://www.googleapis.com/auth/firebase.messaging",
    });

    const send = calls[1]!;
    expect(send.url).toBe("https://fcm.test/v1/projects/my-proj/messages:send");
    expect((send.init.headers as Record<string, string>).authorization).toBe("Bearer ya29.x");
    expect(JSON.parse(send.init.body as string)).toEqual({
      message: { token: "tok", data: { v: "1", event: "{}" }, android: { priority: "HIGH", ttl: "30s" } },
    });

    // token is cached; stale registration is reported
    const res2 = await p.send({ deviceId: "dev_1", token: "tok" }, {}, { ttlSeconds: 1, highPriority: false });
    expect(res2).toMatchObject({ ok: false, unregistered: true });
    expect(calls.filter((c) => c.url === "https://oauth.test/token")).toHaveLength(1);
  });
});
