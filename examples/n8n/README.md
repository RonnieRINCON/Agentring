# n8n + agentring

n8n talks to the relay's REST API with the daemon's API key (`ak_…`, in
`~/.agentring/.env`).

1. In n8n, create a **Header Auth** credential: name `Authorization`, value
   `Bearer ak_...`.
2. Import [`agentring-message.json`](agentring-message.json) (Workflows → Import from
   file), set the relay URL in the HTTP Request node and pick the credential.
3. Run it: the phone gets the message.

Replies: `GET https://<relay>/v1/messages?after=<msg_id>` returns the messages after
that one (yours and the user's; the user's have `"from": "user"`).

A REST call (`POST /v1/calls`) only rings the phone. For a voice conversation, run
the daemon next to n8n and use the **Execute Command** node:
`agentring call "Disk almost full on db-1" --ai --json` (prints the transcript).
