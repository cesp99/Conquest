#!/usr/bin/env python3
"""A first-party ACP agent, for proving the agent panel end to end.

The panel's exit criterion needs a real agent process speaking the Agent
Client Protocol over real pipes — and installing somebody's actual agent is
the owner's call, not the build's. This is the conformance stand-in: ~250
lines of stdlib Python (the Debian guest ships python3, so it needs no
install of any kind), configured through the same `agent_servers` settings
entry any user-supplied agent uses:

    "agent_servers": {
      "Conformance": { "command": "python3", "args": ["/root/conformance-agent.py"] }
    }

Per prompt it exercises every surface the panel has: streamed thought and
message chunks, a plan that progresses, a tool call, a permission request
(so allow/deny and cancellation are all drivable), file access through the
client's own fs capability (so the engine's project-root confinement is on
the path), and a diff on the completed call.

The host test `a_python_conformance_agent_survives_the_whole_flow` in
core/crates/engine/src/acp.rs drives this same file through the production
spawn path, so a wire-shape mistake here is a red test, not a device session.

Wire notes, all load-bearing:
  - One JSON object per line, "jsonrpc":"2.0" on everything, stdout flushed
    per line (block-buffered stdout would arrive as one blob at exit).
  - Field casing is the protocol's: camelCase keys, snake_case enum values,
    and the session/update payload tagged by "sessionUpdate".
  - A request id is echoed verbatim — the client may use strings or numbers.
  - "oldText" on a diff is *omitted* for a new file, never null: the schema
    treats null and absent differently on other fields, so absence is the
    only spelling that cannot be misread.
  - Nothing human-readable ever goes to stdout; logs go to stderr, which the
    engine forwards to logcat.
"""

import json
import os
import sys
import time

CHUNK_PAUSE_SECONDS = 0.15
DEFAULT_TARGET = "AGENT_NOTE.md"


def send(message):
    sys.stdout.write(json.dumps(message) + "\n")
    sys.stdout.flush()


def log(text):
    sys.stderr.write("conformance-agent: " + text + "\n")
    sys.stderr.flush()


def read_message():
    """The next JSON-RPC message, or None on EOF. Unparseable lines are
    skipped with a log rather than fatal: the transport owns framing, and a
    conformance tool that dies on garbage proves nothing about the client."""
    while True:
        line = sys.stdin.readline()
        if line == "":
            return None
        line = line.strip()
        if not line:
            continue
        try:
            return json.loads(line)
        except ValueError:
            log("skipping unparseable line: " + line[:120])


class Cancelled(Exception):
    """The client cancelled the turn; unwind to the prompt handler, which
    answers with stopReason "cancelled" as the spec requires."""


class Agent:
    def __init__(self):
        self.cwd = None
        self.cancelled = False
        self.next_request = 0
        self.tool_calls = 0
        # Per-session ids, "conf-<n>": the spec wants unique ids, and a
        # constant would collide the moment a client opened a second session
        # on the same process.
        self.sessions = 0
        self.session_id = None
        # Config options, the panel's selector chips: a model select and a
        # verbose toggle, remembered so set_config_option visibly sticks.
        self.model = "conf-one"
        self.verbose = False

    def config_options(self):
        return [
            {"id": "model", "name": "Model", "type": "select",
             "category": "model", "currentValue": self.model,
             "options": [
                 {"value": "conf-one", "name": "Conformance One"},
                 {"value": "conf-two", "name": "Conformance Two"},
             ]},
            {"id": "verbose", "name": "Verbose", "type": "boolean",
             "currentValue": self.verbose},
        ]

    # -- outbound ------------------------------------------------------------

    def update(self, update):
        send({
            "jsonrpc": "2.0",
            "method": "session/update",
            "params": {"sessionId": self.session_id, "update": update},
        })

    def chunk(self, kind, text):
        if self.cancelled:
            raise Cancelled()
        self.update({
            "sessionUpdate": kind,
            "content": {"type": "text", "text": text},
        })
        time.sleep(CHUNK_PAUSE_SECONDS)

    def plan(self, *entries):
        # The whole plan every time — the protocol's rule is that the client
        # replaces it, so a partial send would erase the rest.
        self.update({
            "sessionUpdate": "plan",
            "entries": [
                {"content": content, "priority": "medium", "status": status}
                for content, status in entries
            ],
        })

    def request(self, method, params):
        """Send a request and block until its response arrives.

        Anything else that turns up while waiting is handled in place: a
        session/cancel flips the flag (and the wait keeps going — the client
        still owes an answer, by the spec a `cancelled` outcome), and an
        unexpected request gets method-not-found rather than a hang on the
        other side. Returns the "result", or None for an error response —
        which for fs/read_text_file is the ordinary "no such file yet".
        """
        self.next_request += 1
        request_id = "conf-req-%d" % self.next_request
        send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        while True:
            message = read_message()
            if message is None:
                sys.exit(0)
            if message.get("id") == request_id and "method" not in message:
                if "error" in message:
                    log("%s answered with an error: %s" % (method, message["error"]))
                    return None
                return message.get("result")
            self.handle_out_of_band(message)

    def handle_out_of_band(self, message):
        method = message.get("method")
        if method == "session/cancel":
            log("cancel received")
            self.cancelled = True
        elif method == "$/cancel_request":
            pass
        elif "id" in message and method is not None:
            # A request this agent does not serve. Answer, or the client's
            # dispatch waits on it for ever.
            send({
                "jsonrpc": "2.0",
                "id": message["id"],
                "error": {"code": -32601, "message": "method not found: " + method},
            })
        # Stray responses to ids we no longer hold are dropped.

    # -- inbound -------------------------------------------------------------

    def handle(self, message):
        method = message.get("method")
        if method is None or "id" not in message:
            self.handle_out_of_band(message)
            return
        request_id = message["id"]
        params = message.get("params") or {}

        if method == "initialize":
            send({
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {
                    "protocolVersion": 1,
                    "agentCapabilities": {},
                    "authMethods": [],
                    "agentInfo": {"name": "conformance-agent", "version": "1.0.0"},
                },
            })
        elif method == "session/new":
            self.cwd = params.get("cwd") or os.getcwd()
            self.sessions += 1
            self.session_id = "conf-%d" % self.sessions
            self.model = "conf-one"
            self.verbose = False
            log("session %s in %s" % (self.session_id, self.cwd))
            send({"jsonrpc": "2.0", "id": request_id,
                  "result": {"sessionId": self.session_id,
                             "configOptions": self.config_options()}})
            # Slash commands arrive as an update, the way live agents send
            # them (the panel's / popup completes from these).
            self.update({
                "sessionUpdate": "available_commands_update",
                "availableCommands": [
                    {"name": "plan", "description": "Show a plan and stop"},
                    {"name": "echo",
                     "description": "Repeat the text back",
                     "input": {"hint": "text to repeat"}},
                ],
            })
        elif method == "session/set_config_option":
            config = params.get("configId")
            # A select's value id arrives as a bare string; a boolean arrives
            # tagged ({"type": "boolean", "value": …}). Take both.
            value = params.get("value")
            if isinstance(value, dict):
                value = value.get("value")
            if config == "model" and value in ("conf-one", "conf-two"):
                self.model = value
            elif config == "verbose":
                self.verbose = bool(value)
            send({"jsonrpc": "2.0", "id": request_id,
                  "result": {"configOptions": self.config_options()}})
        elif method == "session/prompt":
            stop_reason = self.run_turn(params)
            send({
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {"stopReason": stop_reason},
            })
        else:
            self.handle_out_of_band(message)

    # -- the turn ------------------------------------------------------------

    def run_turn(self, params):
        self.cancelled = False
        blocks = params.get("prompt", [])
        prompt = " ".join(
            block.get("text", "")
            for block in blocks
            if block.get("type") == "text"
        ).strip()
        # Mentions arrive as resource blocks beside the text: embedded file
        # text, or a link. Named back, so the panel's @ flow is visible
        # end to end.
        context = []
        for block in blocks:
            if block.get("type") == "resource":
                uri = (block.get("resource") or {}).get("uri", "")
                context.append(os.path.basename(uri) + " (embedded)")
            elif block.get("type") == "resource_link":
                context.append(block.get("name", "?") + " (link)")
        try:
            if context:
                self.chunk("agent_message_chunk",
                           "Context received: %s. " % ", ".join(context))
            if prompt.startswith("/echo"):
                self.chunk("agent_message_chunk",
                           "Echo [%s]: %s" % (self.model, prompt[5:].strip()))
                return "end_turn"
            if prompt.startswith("/plan"):
                self.plan(("Look around", "in_progress"), ("Report back", "pending"))
                self.chunk("agent_message_chunk", "That is the plan; say more when ready.")
                self.plan(("Look around", "completed"), ("Report back", "completed"))
                return "end_turn"
        except Cancelled:
            log("turn cancelled")
            return "cancelled"
        target = self.pick_target(prompt)
        path = os.path.join(self.cwd or os.getcwd(), target)
        try:
            self.chunk("agent_thought_chunk", "The user wants an edit; %s is the file to touch." % target)
            self.chunk("agent_message_chunk", "I'll make a small edit to `%s`. " % target)
            self.plan(("Read the file", "in_progress"), ("Write the change", "pending"))

            self.tool_calls += 1
            tool_id = "t-%d" % self.tool_calls
            self.update({
                "sessionUpdate": "tool_call",
                "toolCallId": tool_id,
                "title": "Edit " + target,
                "kind": "edit",
                "status": "pending",
            })
            outcome = self.request("session/request_permission", {
                "sessionId": self.session_id,
                "toolCall": {
                    "toolCallId": tool_id,
                    "title": "Edit " + target,
                    "kind": "edit",
                    "status": "pending",
                },
                "options": [
                    {"optionId": "allow", "name": "Allow", "kind": "allow_once"},
                    {"optionId": "reject", "name": "Reject", "kind": "reject_once"},
                ],
            })
            decision = (outcome or {}).get("outcome") or {}
            if self.cancelled or decision.get("outcome") == "cancelled":
                raise Cancelled()
            if decision.get("optionId") != "allow":
                self.plan(("Read the file", "completed"), ("Write the change", "completed"))
                self.chunk("agent_message_chunk", "Understood — leaving %s alone." % target)
                return "end_turn"

            self.update({"sessionUpdate": "tool_call_update", "toolCallId": tool_id,
                         "status": "in_progress"})
            read = self.request("fs/read_text_file", {"sessionId": self.session_id, "path": path})
            old_text = None if read is None else read.get("content", "")
            # A cancel that arrived while the read was in flight stops the
            # turn *here* — a cancelled turn must not write.
            if self.cancelled:
                raise Cancelled()
            self.plan(("Read the file", "completed"), ("Write the change", "in_progress"))

            new_text = (old_text or "") + "Edited by the conformance agent: %s\n" % prompt
            wrote = self.request("fs/write_text_file", {
                "sessionId": self.session_id, "path": path, "content": new_text,
            })
            if wrote is None:
                self.update({"sessionUpdate": "tool_call_update", "toolCallId": tool_id,
                             "status": "failed"})
                self.chunk("agent_message_chunk", "The editor refused that write.")
                return "end_turn"

            diff = {"type": "diff", "path": path, "newText": new_text}
            if old_text is not None:
                diff["oldText"] = old_text
            self.update({
                "sessionUpdate": "tool_call_update",
                "toolCallId": tool_id,
                "status": "completed",
                "content": [diff],
            })
            self.plan(("Read the file", "completed"), ("Write the change", "completed"))
            self.chunk("agent_message_chunk", "Done — `%s` updated." % target)
            return "end_turn"
        except Cancelled:
            log("turn cancelled")
            return "cancelled"

    @staticmethod
    def pick_target(prompt):
        """The last dotted token of the prompt names the file; everything that
        could climb out of the project is cut down to a basename. The engine
        refuses escapes anyway (its resolves_inside guard), but a conformance
        agent should not be the thing probing it."""
        target = DEFAULT_TARGET
        for token in prompt.split():
            token = token.strip("\"'`.,;:!?")
            name = os.path.basename(token)
            if "." in name and name not in (".", "..") and not name.startswith("."):
                target = name
        return target


def main():
    agent = Agent()
    while True:
        message = read_message()
        if message is None:
            return 0
        agent.handle(message)


if __name__ == "__main__":
    sys.exit(main())
