# Struktura dat databáze GitHub Copilot Agent Sessions

Tento dokument popisuje formát a obsah databázových souborů, které GitHub Copilot plugin
pro JetBrains IDE ukládá na disk.

---

## Umístění databází

```
~/.config/github-copilot/
  ai/                          # Android Studio (starší)
    chat-agent-sessions/
      <session-id>/
        copilot-agent-sessions-nitrite.db
  iu/                          # IntelliJ IDEA
    chat-agent-sessions/
      <session-id>/
        copilot-agent-sessions-nitrite.db
  cl/                          # CLion, apod.
    chat-agent-sessions/
      <session-id>/
        copilot-agent-sessions-nitrite.db
```

Každý soubor `.db` odpovídá **jednomu projektu** (workspace). Soubor je zamčen, pokud je
příslušné IDE právě spuštěno. Formát je MVStore (H2 databáze), interně přes Nitrite ORM.

---

## Tabulky (MVStore mapy)

Každá databáze obsahuje tyto kolekce:

| Název mapy | Obsah |
|---|---|
| `NtAgentSession` | Metadata chatovacích sessions (konverzací) |
| `NtAgentTurn` | Jednotlivé výměny zpráv (otázka + odpověď) |
| `NtAgentWorkingSetItem` | Pracovní sada souborů (indexová tabulka) |

---

## `NtAgentSession` — Konverzace

Každý záznam odpovídá jednomu chatu (konverzaci).

| Pole | Typ | Popis |
|---|---|---|
| `id` | String | UUID konverzace |
| `name.value` | String | Název konverzace (generovaný nebo zadaný) |
| `user` | String | Přihlašovací jméno uživatele |
| `createdAt` | Long | Timestamp vytvoření (epoch ms) |
| `modifiedAt` | Long | Timestamp poslední změny (epoch ms) |
| `turns` | List | Embedded seznam turnů (starší formát) |

---

## `NtAgentTurn` — Výměna zpráv

Každý záznam = jedna otázka + jedna odpověď. Hlavní datové pole jsou JSON stringy.

### Skalární pole dokumentu

| Pole | Typ | Popis |
|---|---|---|
| `id` | String | UUID turnu |
| `sessionId` | String | Reference na `NtAgentSession.id` |
| `createdAt` | Long | Timestamp vytvoření (epoch ms) |
| `deletedAt` | Long/null | Pokud není null, turn je smazán |
| `rating` | Integer/null | Hodnocení odpovědi uživatelem (kladné = 👍, záporné = 👎) |
| `request.chatMode` | String | Režim chatu (`Ask`, `Agent`, …) |
| `request.model` | String/null | Požadovaný model |
| `request.modelType` | String/null | Typ modelu |
| `request.stringContent` | String | Textový obsah dotazu (zjednodušený) |
| `request.contents` | String | **JSON** s plnou strukturou dotazu (viz níže) |
| `request.user` | String | Uživatelské jméno |
| `request.type` | String | Typ požadavku |
| `request.status` | String | Stav zpracování |
| `response.modelInformation.modelName` | String | Název použitého modelu (např. `Gemini 3.1 Pro`) |
| `response.modelInformation.modelBillingMultiplier` | String | Koeficient účtování |
| `response.stringContent` | String | Textový obsah odpovědi (zjednodušený) |
| `response.contents` | String | **JSON** s plnou strukturou odpovědi (viz níže) |
| `response.status` | String | Stav odpovědi |
| `response.chatMode` | String | Režim chatu v odpovědi |

---

## Struktura JSON obsahu (`request.contents` / `response.contents`)

JSON má vícevrstvou strukturu s typovými uzly. Po parsování vypadá takto:

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

> **Poznámka:** Pole `value` a `data` mohou být vnořené JSON stringy, které jsou při parsování
> automaticky rozbaleny.

---

## Datové typy v JSON obsahu

### `Markdown`
Hlavní textová odpověď v Markdown formátu.

```json
{
  "type": "Markdown",
  "data": {
    "text": "Odpověď v **Markdownu**...",
    "annotations": []
  }
}
```

### `Thinking`
Interní úvahy reasoning modelu (chain-of-thought). Viditelné pouze u modelů s podporou
reasoning (Gemini, Claude s thinking, o1/o3 apod.).

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

| Pole | Popis |
|---|---|
| `title` | Krátký souhrn kroku přemýšlení |
| `content` | Plný text v Markdown formátu |
| `isComplete` | Zda je krok dokončen |

### `Steps`
Kroky provedené agentem (sběr kontextu, čtení souborů, …).

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

| Pole | Popis |
|---|---|
| `id` | Interní identifikátor kroku |
| `title` | Název kroku |
| `status` | `completed` nebo `failed` |
| `error.message` | Chybová zpráva (pokud `status == failed`) |

### `References`
Soubory použité jako kontext pro odpověď (např. zdrojové soubory, JAR archivy).

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
Kolo agenta — obsahuje volání nástrojů (tool calls) a jejich výsledky.

```json
{
  "type": "AgentRound",
  "data": {
    "roundId": 1,
    "reply": "text odpovědi po tool calls",
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
Soubory, které agent editoval. Obsahuje **původní i nový obsah** každého souboru.

```json
{
  "type": "WorkingSet",
  "data": [
    {
      "file": "file:///home/user/project/src/Foo.java",
      "originalContent": "package ...\n\n// původní obsah",
      "newContent": "package ...\n\n// nový obsah po editaci"
    }
  ]
}
```

| Pole | Popis |
|---|---|
| `file` | URI souboru (`file://`) |
| `originalContent` | Obsah před editací |
| `newContent` | Obsah po editaci agenta |

> Exportér zapisuje `newContent` každého souboru do podsložky chatu a vytvoří Markdown odkaz.

### `Error`
Chyba při generování odpovědi (přetečení kontextu, selhání modelu, …).

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
Informace o aktivním souboru a nastavení modelu v době odeslání dotazu (z `request.contents`).

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
Skryté/odstraněné reference (interní metadata).

```json
{
  "type": "Hide",
  "data": [{ "type": "References", ... }]
}
```

---

## Struktura výstupu exportéru

```
chat-export/
  _assets/                          # Sdílená složka s obsahem editovaných souborů
    2026-05-03_Název_chatu/         # Podsložka pojmenovaná stejně jako chat
      soubor.c                      # Obsah po editaci (newContent)
      soubor.orig.c                 # Obsah před editací (originalContent) — jen pokud se liší
      soubor.h
      soubor.orig.h
  basic/                            # Prostý Markdown
    2026-05-03_Název_chatu.md       # Jeden soubor = jedna konverzace
  styled/                           # Markdown se styly a collapsible <details>
    2026-05-03_Název_chatu.md
```

Odkaz z `basic/Chat.md` nebo `styled/Chat.md` na assets má tvar:
```markdown
[`~/projekt/soubor.c`](../_assets/Chat/soubor.c) ([orig](../_assets/Chat/soubor.orig.c))
```

### Co je exportováno

| Sekce v Markdownu | Zdroj | Podmínka zobrazení |
|---|---|---|
| Název, uživatel, datum | `NtAgentSession` | vždy |
| Dotaz (blockquote) | `request.stringContent` / `Markdown` z `request.contents` | vždy |
| **Mode** | `request.chatMode` | při změně oproti předchozímu turnu |
| **Model** | `response.modelInformation.modelName` | při změně oproti předchozímu turnu |
| **References** | `References` z `response.contents` | při změně oproti předchozímu turnu |
| **Steps** | `Steps` z `response.contents` | pokud neprázdný |
| **Thinking** | `Thinking` z `response.contents` | pokud neprázdný |
| **Edited files** | `WorkingSet` z obou JSON | pokud neprázdný (s odkazem na soubor) |
| **⚠️ Error** | `Error` z `response.contents` | pokud přítomen |
| **Rating** | `rating` z dokumentu | pokud není null |
| Odpověď | `response.stringContent` / `Markdown`/`AgentRound` z `response.contents` | vždy |
