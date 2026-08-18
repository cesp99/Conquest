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
        self.cancelled = False
        self.next_request = 0
        self.tool_calls = 0
        # Per-session ids, "conf-<n>": the spec wants unique ids, and a
        # constant would collide the moment a client opened a second session
        # on the same process.
        self.sessions = 0
        # **State per session, keyed by id.** One client process holds several
        # threads against one agent — the panel does exactly that — and every
        # one of them is a separate ACP session over the same pipes. Holding a
        # single `self.session_id` meant a prompt for conf-1 emitted updates
        # tagged conf-2, so a whole turn (chunks, plan, tool call, permission
        # request) landed in the wrong thread's transcript.
        self.state = {}

    def session(self, session_id):
        """The state for `session_id`, created on first sight."""
        return self.state.setdefault(
            session_id,
            {"cwd": os.getcwd(), "model": "conf-one", "verbose": False},
        )

    def config_options(self, session_id):
        state = self.session(session_id)
        return [
            {"id": "model", "name": "Model", "type": "select",
             "category": "model", "currentValue": state["model"],
             "options": [
                 {"value": "conf-one", "name": "Conformance One"},
                 {"value": "conf-two", "name": "Conformance Two"},
             ]},
            {"id": "verbose", "name": "Verbose", "type": "boolean",
             "currentValue": state["verbose"]},
        ]

    # -- outbound ------------------------------------------------------------

    def update(self, session_id, update):
        send({
            "jsonrpc": "2.0",
            "method": "session/update",
            "params": {"sessionId": session_id, "update": update},
        })

    def chunk(self, session_id, kind, text):
        if self.cancelled:
            raise Cancelled()
        self.update(session_id, {
            "sessionUpdate": kind,
            "content": {"type": "text", "text": text},
        })
        time.sleep(CHUNK_PAUSE_SECONDS)

    def plan(self, session_id, *entries):
        # The whole plan every time — the protocol's rule is that the client
        # replaces it, so a partial send would erase the rest.
        self.update(session_id, {
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
            self.sessions += 1
            session_id = "conf-%d" % self.sessions
            state = self.session(session_id)
            state["cwd"] = params.get("cwd") or os.getcwd()
            log("session %s in %s" % (session_id, state["cwd"]))
            send({"jsonrpc": "2.0", "id": request_id,
                  "result": {"sessionId": session_id,
                             "configOptions": self.config_options(session_id)}})
            # Slash commands arrive as an update, the way live agents send
            # them (the panel's / popup completes from these).
            self.update(session_id, {
                "sessionUpdate": "available_commands_update",
                "availableCommands": [
                    {"name": "plan", "description": "Show a plan and stop"},
                    {"name": "echo",
                     "description": "Repeat the text back",
                     "input": {"hint": "text to repeat"}},
                ],
            })
        elif method == "session/set_config_option":
            session_id = params.get("sessionId")
            state = self.session(session_id)
            config = params.get("configId")
            # A select's value id arrives as a bare string; a boolean arrives
            # tagged ({"type": "boolean", "value": …}). Take both.
            value = params.get("value")
            if isinstance(value, dict):
                value = value.get("value")
            if config == "model" and value in ("conf-one", "conf-two"):
                state["model"] = value
            elif config == "verbose":
                state["verbose"] = bool(value)
            send({"jsonrpc": "2.0", "id": request_id,
                  "result": {"configOptions": self.config_options(session_id)}})
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
        # Every reply is stamped with the session the prompt came in on —
        # never a remembered one, or a second thread's turn would land in the
        # first thread's transcript.
        session_id = params.get("sessionId")
        state = self.session(session_id)
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
                self.chunk(session_id, "agent_message_chunk",
                           "Context received: %s. " % ", ".join(context))
            if prompt.startswith("/echo"):
                self.chunk(session_id, "agent_message_chunk",
                           "Echo [%s]: %s" % (state["model"], prompt[5:].strip()))
                return "end_turn"
            if prompt.startswith("/plan"):
                self.plan(session_id,
                          ("Look around", "in_progress"), ("Report back", "pending"))
                self.chunk(session_id, "agent_message_chunk",
                           "That is the plan; say more when ready.")
                self.plan(session_id,
                          ("Look around", "completed"), ("Report back", "completed"))
                return "end_turn"
        except Cancelled:
            log("turn cancelled")
            return "cancelled"
        target = self.pick_target(prompt)
        path = os.path.join(state["cwd"], target)
        try:
            self.chunk(session_id, "agent_thought_chunk",
                       "The user wants an edit; %s is the file to touch." % target)
            self.chunk(session_id, "agent_message_chunk",
                       "I'll make a small edit to `%s`. " % target)
            self.plan(session_id,
                      ("Read the file", "in_progress"), ("Write the change", "pending"))

            self.tool_calls += 1
            tool_id = "t-%d" % self.tool_calls
            self.update(session_id, {
                "sessionUpdate": "tool_call",
                "toolCallId": tool_id,
                "title": "Edit " + target,
                "kind": "edit",
                "status": "pending",
            })
            outcome = self.request("session/request_permission", {
                "sessionId": session_id,
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
                self.plan(session_id,
                          ("Read the file", "completed"),
                          ("Write the change", "completed"))
                self.chunk(session_id, "agent_message_chunk", "Understood — leaving %s alone." % target)
                return "end_turn"

            self.update(session_id, {"sessionUpdate": "tool_call_update",
                                     "toolCallId": tool_id,
                                     "status": "in_progress"})
            read = self.request("fs/read_text_file", {"sessionId": session_id, "path": path})
            old_text = None if read is None else read.get("content", "")
            # A cancel that arrived while the read was in flight stops the
            # turn *here* — a cancelled turn must not write.
            if self.cancelled:
                raise Cancelled()
            self.plan(session_id,
                      ("Read the file", "completed"),
                      ("Write the change", "in_progress"))

            new_text = (old_text or "") + "Edited by the conformance agent: %s\n" % prompt
            wrote = self.request("fs/write_text_file", {
                "sessionId": session_id, "path": path, "content": new_text,
            })
            if wrote is None:
                self.update(session_id, {"sessionUpdate": "tool_call_update",
                                         "toolCallId": tool_id,
                                         "status": "failed"})
                self.chunk(session_id, "agent_message_chunk", "The editor refused that write.")
                return "end_turn"

            diff = {"type": "diff", "path": path, "newText": new_text}
            if old_text is not None:
                diff["oldText"] = old_text
            self.update(session_id, {
                "sessionUpdate": "tool_call_update",
                "toolCallId": tool_id,
                "status": "completed",
                "content": [diff],
            })
            self.plan(session_id,
                          ("Read the file", "completed"),
                          ("Write the change", "completed"))
            self.chunk(session_id, "agent_message_chunk", "Done — `%s` updated." % target)
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
