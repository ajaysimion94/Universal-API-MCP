# MCP Client Guide

This is the integration contract for an MCP-compatible LLM client. The server exposes Streamable
HTTP, dynamic tools, guide resources, and reusable prompts. It is a local/trusted-network development
service until Phase 6 authentication lands.

## Connect

Start the server and configure the client with this endpoint:

~~~text
http://127.0.0.1:8080/mcp
~~~

Use a standard MCP Streamable HTTP transport. Complete the normal initialize exchange first, then
send the notifications/initialized notification if your client implementation requires it. The
server advertises tools, resources, and prompts capabilities.

Do not publish this endpoint or tunnel it to an untrusted network. There is no identity, authorization,
or ACL enforcement yet.

## Required start-of-session sequence

1. Call resources/list.
2. Read mcp://enterprise-mcp/guides/operating-guide.
3. Read mcp://enterprise-mcp/guides/llm-playbook.json when structured instructions are useful.
4. Call tools/list and inspect the current schemas. Imported tools can be enabled, disabled, or
   changed while the server is running.
5. Optionally call prompts/get for orient-to-enterprise-mcp or execute-grounded-task.

The resource is a workflow contract, not a cached tool catalogue. The response from tools/list is
the authoritative definition of what can be called now.

## LLM operating rules

For a question requiring enterprise context:

1. Call search-knowledge-base with a focused query and appropriate topK.
2. Base your response on the returned excerpts and mention the source name/path when sharing evidence.
3. If the returned context cannot support an answer, say so and ask for the missing detail. Never
   fabricate document content, a citation, tool output, or a completed action.

For an API task:

1. Read the tool's schema from tools/list before composing arguments.
2. Call a read-only tool only with valid, current arguments.
3. When a write tool returns a preview and confirmation token, show the resolved action to the person.
4. Wait for explicit, current approval of that exact preview.
5. Only then call confirm-action with the supplied token. Tokens are short-lived and single-use.

Never treat a prior approval as approval for changed arguments, reuse a token, guess an unknown
parameter, or call the confirmation tool merely because an action seems sensible.

## Resources and prompts

| Protocol item | Name / URI | Purpose |
| --- | --- | --- |
| Resource | mcp://enterprise-mcp/guides/operating-guide | Concise Markdown operating procedure and safety rules. |
| Resource | mcp://enterprise-mcp/guides/llm-playbook.json | Structured startup, grounding, and action-safety checklist. |
| Prompt | orient-to-enterprise-mcp | Starts a safe, evidence-first session. |
| Prompt | execute-grounded-task | Accepts a required task argument and frames the task around evidence and approval rules. |
| Tool | search-knowledge-base | Retrieves cited RAG context. |
| Tool | confirm-action | Executes a previously previewed write only after explicit human approval. |

Imported API tools are not listed here because they are dynamic. Always discover them through
tools/list.

## Example client configuration

The exact configuration format is client-specific. In a JSON-based client registry, the important
part is an HTTP/Streamable HTTP entry pointing at the local endpoint:

~~~json
{
  "mcpServers": {
    "enterprise-mcp": {
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
~~~

If a client supports MCP prompt or resource discovery, enable both. If it only supports tools, put the
operating-guide URI into the client's startup instructions and retain the same write-confirmation rule.

## Failure handling

- **Connection refused:** confirm the server is running and stays bound to 127.0.0.1:8080.
- **No search evidence:** search may have no indexed material or required plugins may be inactive;
  report this rather than substituting unstated knowledge.
- **Tool unavailable:** refresh with tools/list; it may have been disabled or removed.
- **Confirmation fails:** do not retry a token blindly. Explain that it may be expired, used, or no
  longer valid and request a fresh preview.
- **Unexpected response shape:** preserve the error context, avoid mutating calls, and ask the person
  how to proceed if the retry changes the intended action.
