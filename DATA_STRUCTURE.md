# GitHub Copilot Agent Sessions — Database Structure

This document describes the format and contents of the database files stored on disk by the
GitHub Copilot plugin for JetBrains IDEs.

---

## Database Locations

```
~/.config/github-copilot/
  ai/                          # Android Studio (older)
    chat-agent-sessions/
      <session-id>/
        copilot-agent-sessions-nitrite.db
  iu/                          # IntelliJ IDEA
    chat-agent-sessions/
      <session-id>/
        copilot-agent-sessions-nitrite.db
  cl/                          # CLion, etc.
    chat-agent-sessions/
      <session-id>/
        copilot-agent-sessions-nitrite.db
```

Each `.db` file corresponds to **one project** (workspace). The file is locked while the
corresponding IDE is running. The format is MVStore (H2 database), accessed internally via
Nitrite ORM.

---

## Collections (MVStore Maps)

Each database contains the following collections:

| Map name | Contents |
|---|---|
| `NtAgentSession` | Metadata for chat sessions (conversations) |
| `NtAgentTurn` | Individual message exchanges (request + response) |
| `NtAgentWorkingSetItem` | Working set of files (index table) |

---

## `NtAgentSession` — Conversation

Each record corresponds to one chat (conversation).

| Field | Type | Description |
|---|---|---|
| `id` | String | Conversation UUID |
| `name.value` | String | Conversation title (generated or user-provided) |
| `user` | String | Login name of the user |
| `createdAt` | Long | Creation timestamp (epoch ms) |
| `modifiedAt` | Long | Last-modified timestamp (epoch ms) |
| `turns` | List | Embedded list of turns (legacy format) |

---

## `NtAgentTurn` — Message Exchange

Each record = one request + one response. The main data fields are JSON strings.

### Scalar document fields

| Field | Type | Description |
|---|---|---|
| `id` | String | Turn UUID |
| `sessionId` | String | Reference to `NtAgentSession.id` |
| `createdAt` | Long | Creation timestamp (epoch ms) |
| `deletedAt` | Long/null | If non-null, the turn has been deleted |
| `rating` | Integer/null | User rating of the response (positive = 👍, negative = 👎) |
| `request.chatMode` | String | Chat mode (`Ask`, `Agent`, …) |
| `request.model` | String/null | Requested model |
| `request.modelType` | String/null | Model type |
| `request.stringContent` | String | Plain-text content of the request (simplified) |
| `request.contents` | String | **JSON** with the full request structure (see below) |
| `request.user` | String | Username |
| `request.type` | String | Request type |
| `request.status` | String | Processing status |
| `response.modelInformation.modelName` | String | Name of the model used (e.g. `Gemini 3.1 Pro`) |
| `response.modelInformation.modelBillingMultiplier` | String | Billing multiplier |
| `response.stringContent` | String | Plain-text content of the response (simplified) |
| `response.contents` | String | **JSON** with the full response structure (see below) |
| `response.status` | String | Response status |
| `response.chatMode` | String | Chat mode in the response |

---

## JSON Content Structure (`request.contents` / `response.contents`)

The JSON has a multi-layered structure with typed nodes. After parsing it looks like this:

```
{
  "__first__": {
    "type": "Subgraph",
    "value": {
      "<uuid>": {
        "type": "Value",
        "value": {
          "type": "<DataType>",
          "data": <data>
        }
      },
      ...
    }
  },
  "<uuid>": {
    "type": "Value",
    "value": {
      "type": "AgentRound",
      "data": { "roundId": 1, "toolCalls": [...] }
    }
  },
  "__last__": {
    "type": "Subgraph",
    "value": { ... }
  }
}
```

> **Note:** The `value` and `data` fields may contain nested JSON strings that are
> automatically expanded during parsing.

---

## Data Types in JSON Content

### `Markdown`
The main text response in Markdown format.

```json
{
  "type": "Markdown",
  "data": {
    "text": "Response in **Markdown**...",
    "annotations": []
  }
}
```

### `Thinking`
Internal reasoning steps of a reasoning model (chain-of-thought). Only visible for models
with reasoning support (Gemini, Claude with thinking, o1/o3, etc.).

```json
{
  "type": "Thinking",
  "data": {
    "title": "Reviewed the implementation",
    "id": "",
    "content": "**Checking the code...**\n\n...",
    "isComplete": true
  }
}
```

| Field | Description |
|---|---|
| `title` | Brief summary of the thinking step |
| `content` | Full text in Markdown format |
| `isComplete` | Whether the step has finished |

### `Steps`
Steps performed by the agent (collecting context, reading files, …).

```json
{
  "type": "Steps",
  "data": [
    {
      "id": "collect-context",
      "title": "Collecting context",
      "status": "completed",
      "error": { "message": "..." }
    }
  ]
}
```

| Field | Description |
|---|---|
| `id` | Internal step identifier |
| `title` | Step name |
| `status` | `completed` or `failed` |
| `error.message` | Error message (when `status == failed`) |

### `References`
Files used as context for the response (e.g. source files, JAR archives).

```json
{
  "type": "References",
  "data": [
    {
      "type": "file",
      "reference": { "uri": "file:///home/user/project/Foo.java" }
    }
  ]
}
```

### `AgentRound`
One agent round — contains tool calls and their results.

```json
{
  "type": "AgentRound",
  "data": {
    "roundId": 1,
    "reply": "text of the reply after tool calls",
    "toolCalls": [
      {
        "status": "completed",
        "input": { ... },
        "result": [{ "type": "text", ... }]
      }
    ]
  }
}
```

### `WorkingSet`
Files edited by the agent. Contains **both the original and new content** of each file.

```json
{
  "type": "WorkingSet",
  "data": [
    {
      "file": "file:///home/user/project/src/Foo.java",
      "originalContent": "package ...\n\n// original content",
      "newContent": "package ...\n\n// content after agent edit"
    }
  ]
}
```

| Field | Description |
|---|---|
| `file` | File URI (`file://`) |
| `originalContent` | Content before editing |
| `newContent` | Content after the agent's edit |

> The exporter writes the `newContent` of each file to the chat's assets subfolder and
> creates a Markdown link. The `originalContent` is saved as `file.orig.ext` only if it
> differs from the new content.

### `Error`
Error during response generation (context overflow, model failure, …).

```json
{
  "type": "Error",
  "data": {
    "code": 400,
    "message": "maxTokens must be larger than the ellipsis length",
    "model": "auto"
  }
}
```

### `FixedContextPanel`
Information about the active file and model settings at the time the request was sent
(from `request.contents`).

```json
{
  "type": "FixedContextPanel",
  "data": {
    "currentFileUri": "file:///home/user/project/Foo.java",
    "modelName": "Auto",
    "isVisionEnabled": false,
    "modelSupportsImages": false,
    "references": []
  }
}
```

### `Hide`
Hidden/removed references (internal metadata).

```json
{
  "type": "Hide",
  "data": [{ "type": "References", ... }]
}
```

---

## Exporter Output Structure

```
chat-export/
  _assets/                          # Shared folder with edited file contents
    2026-05-03_Chat_title/          # Subfolder named after the chat
      file.c                        # Content after editing (newContent)
      file.orig.c                   # Content before editing (originalContent) — only if different
      file.h
      file.orig.h
  basic/                            # Plain Markdown
    2026-05-03_Chat_title.md        # One file = one conversation
  styled/                           # Markdown with styles and collapsible <details>
    2026-05-03_Chat_title.md
```

Links from `basic/Chat.md` or `styled/Chat.md` to assets use the form:
```markdown
[`~/project/file.c`](../_assets/Chat/file.c) ([orig](../_assets/Chat/file.orig.c))
```

### What is exported

| Markdown section | Source | Shown when |
|---|---|---|
| Title, user, date | `NtAgentSession` | always |
| Request (blockquote) | `request.stringContent` / `Markdown` from `request.contents` | always |
| **Mode** | `request.chatMode` | changed compared to previous turn |
| **Model** | `response.modelInformation.modelName` | changed compared to previous turn |
| **References** | `References` from `response.contents` | changed compared to previous turn |
| **Steps** | `Steps` from `response.contents` | non-empty |
| **Thinking** | `Thinking` from `response.contents` | non-empty |
| **Edited files** | `WorkingSet` from both JSONs | non-empty (with link to file) |
| **⚠️ Error** | `Error` from `response.contents` | present |
| **Rating** | `rating` field on document | non-null |
| Response | `response.stringContent` / `Markdown`/`AgentRound` from `response.contents` | always |

```