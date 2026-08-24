# VS Code Inline Completions — Research & Implementation Plan

## 1. Research: How VS Code Implements Inline Suggestions

### 1.1 Architecture Overview (MVC + Reactive Observables)

VS Code's inline completions system lives in:
```
src/vs/editor/contrib/inlineCompletions/browser/
```

It follows a **reactive MVC pattern** built on `vs/base/common/observable.js`:

| Component | Role | VS Code Path |
|-----------|------|--------------|
| `InlineCompletionsController` | Entry point editor contribution. Manages lifecycle, handles commands (`editor.action.inlineSuggest.trigger`), coordinates user input ↔ model | `controller/inlineCompletionsController.ts` |
| `InlineCompletionsModel` | Core state machine. Tracks active completion, cursor position, suggest widget state. Decides what ghost text to render | `model/inlineCompletionsModel.ts` |
| `InlineCompletionsSource` | Fetches completions from registered `InlineCompletionsProvider` instances. Manages cancellation tokens and lifecycle | `model/inlineCompletionsSource.ts` |
| `InlineEditsView` | Renders complex inline edits (multi-line, deletions, side-by-side diffs) using decorations + content widgets | `view/inlineEdits/inlineEditsView.ts` |
| `GhostText` | Data model for simple suffix-only completions. Rendered as view decorations that inject "virtual" text without modifying the `TextModel` | `model/ghostText.ts` |
| `computeGhostText` | Computes the visual delta between current document text and suggested completion | `model/computeGhostText.ts` |

### 1.2 Two Visual Modes

#### A. Ghost Text (Simple Completions)
- Used for **append-only** suggestions (text added at cursor, no deletions)
- Rendered as **view decorations** — virtual text injected into the editor layout
- The underlying `TextModel` is **NOT modified** until the user accepts
- Styled as dimmed/grey italic text inline with the cursor
- Accept with `Tab`

#### B. Inline Edits (Complex AI Refactorings)
- Used for **multi-line changes, deletions, or non-suffix edits**
- Three sub-views:
  - **Word Replacements** — inline word-level swaps
  - **Side-by-Side** — original code and suggested replacement shown in split/overlaid fashion
  - **Line Replacement** — whole line(s) replaced with diff visualization
  - **Custom** — for cross-file edits (jump-to-location + edit)
- Deletion view: strikethroughs or specialized gutter markers
- Used prominently by Copilot's "Next Edit Suggestions" (NES)

### 1.3 State Machine (`InlineCompletionsModel`)

The model reacts to three observable inputs:
1. **Text Model Changes** — `ITextModel.onDidChangeContent` triggers re-evaluation
2. **Cursor Position** — determines where ghost text is anchored
3. **Suggest Widget State** — if standard autocomplete is visible, the model can "yield" or "augment"

#### Yield-To Mechanism
When both the Suggest Widget and Inline Completions are active:
- The model checks `yieldToSuggestWidget` property
- It can either hide the inline completion or show a preview of the selected suggest item as ghost text
- Configurable via `editor.inlineSuggest.mode`: `prefix` | `subword` | `subwordSmart`

#### State Union Type
```typescript
type InlineCompletionState =
  | { kind: 'ghostText'; edits; primaryGhostText; ghostTexts; suggestItem; inlineSuggestion }
  | { kind: 'inlineEdit'; edits; inlineSuggestion; cursorAtInlineEdit; nextEditUri }
  | undefined;
```

### 1.4 Acceptance Modes

| Mode | Trigger | Behavior |
|------|---------|----------|
| Full Acceptance | `Tab` | Calls `model.accept()` — applies full `ISingleEditOperation` to document |
| Next Word | `Cmd+→` (partial) | Accepts up to the next word boundary |
| Next Line | `Cmd+Enter` (partial) | Accepts up to the next newline |
| Dismiss | Any other key / cursor move | Hides ghost text without applying |

### 1.5 Extension API (Provider Model)

Extensions register an `InlineCompletionsProvider`:

```typescript
interface InlineCompletionsProvider {
  provideInlineCompletions(
    document: TextDocument,
    position: Position,
    context: InlineCompletionContext,
    token: CancellationToken
  ): ProviderResult<InlineCompletionList>;

  disposeInlineCompletions(completions: InlineCompletionList): void;
}
```

**Communication flow:**
```
Extension Host (ExtHostLanguageFeatures)
  → $provideInlineCompletions (RPC)
    → MainThreadLanguageFeatures
      → ILanguageFeaturesService
        → InlineCompletionsSource
          → InlineCompletionsModel
            → Ghost Text / Inline Edits View
```

### 1.6 Key Configuration Options

| Setting | Description |
|---------|-------------|
| `editor.inlineSuggest.enabled` | Enable/disable inline completions |
| `editor.inlineSuggest.fontFamily` | Font for ghost text |
| `editor.inlineSuggest.mode` | `prefix` / `subword` / `subwordSmart` |
| `editor.inlineSuggest.showToolbar` | `always` / `never` / `onHover` |
| `editor.inlineSuggest.suppressSuggestions` | Hide standard autocomplete when inline is showing |
| `editor.inlineSuggest.syntaxHighlightingEnabled` | Syntax highlighting for ghost text |
| `editor.inlineSuggest.minShowDelay` | Minimum delay before showing |

### 1.7 Next Edit Suggestions (NES)

Copilot NES is the evolution of inline suggestions:
- Predicts **where** the next edit should happen (not just at cursor)
- Predicts **what** the edit should be
- Can span a single symbol, a line, or multiple lines
- `Tab` to navigate to the suggested location and accept
- Uses `InlineEditItem` with `jumpTo` action for cross-file edits

---

## 2. Current State of Your NetBeans Plugin

### 2.1 What You Have

| File | Role | VS Code Equivalent |
|------|------|-------------------|
| `GhostTextPainter.java` | Renders grey italic ghost text via Swing `Highlighter` API | `GhostText` + view decorations |
| `InlineCompletionService.java` | Singleton service: editor tracking, debounce, request lifecycle, Tab accept | `InlineCompletionsController` + `InlineCompletionsModel` |
| `ClaudeCompletionClient.java` | Spawns CLI process, builds FIM/comment prompts, parses NDJSON | `InlineCompletionsProvider` + `InlineCompletionsSource` |
| `InlineInstructionDialog.java` | Ctrl+I instruction dialog | (No direct VS Code equivalent — closer to Copilot Chat inline) |
| `TriggerCompletionAction.java` | Manual trigger action | `editor.action.inlineSuggest.trigger` |
| `ShowInstructionAction.java` | Shows instruction dialog | N/A |

### 2.2 What's Missing vs VS Code

| Feature | VS Code | Your Plugin |
|---------|---------|-------------|
| Partial acceptance (next word/line) | ✅ | ❌ — only full Tab accept |
| Ghost text as virtual text (no doc mutation) | ✅ View decorations | ⚠️ Highlighter (visual only, but no partial accept) |
| Multi-suggestion cycling | ✅ Arrow keys | ❌ Single suggestion only |
| Inline edits (deletions, replacements) | ✅ Side-by-side, strikethrough | ❌ Append-only |
| Yield-to suggest widget | ✅ | ❌ No coordination with NetBeans completion |
| Debounce on every keystroke | ✅ Configurable | ⚠️ Only on `//` comment triggers |
| Syntax highlighting in ghost text | ✅ | ❌ Plain grey italic |
| Cancellation when cursor moves | ✅ Observable-based | ⚠️ Basic cancel on new request |
| Next Edit Suggestions (cross-location) | ✅ | ❌ |
| Toolbar on hover (accept/reject buttons) | ✅ | ❌ |

---

## 3. Implementation Plan

### Phase 1: Core Ghost Text Improvements (Low effort, high impact)

#### 1.1 Partial Acceptance — Next Word & Next Line
- **Goal:** Allow `Tab` for full accept, `Ctrl+→` for next word, `Ctrl+Enter` for next line
- **Changes:** `InlineCompletionService.java` — add key handlers for partial accept
- **Logic:** Split suggestion text at word boundaries / newlines, insert only the accepted portion, keep remaining as ghost text

#### 1.2 Dismiss on Cursor Move (not just any key)
- **Goal:** Dismiss ghost text when cursor moves (arrow keys, click) — currently any non-Tab key dismisses
- **Changes:** `InlineCompletionService.java` — add `CaretListener` to detect position change
- **VS Code pattern:** Model reacts to cursor position observable

#### 1.3 Debounce for All Triggers (not just comments)
- **Goal:** Trigger completions on any typing, with configurable debounce
- **Changes:** `InlineCompletionService.java` — remove `isCommentTrigger` gate, add configurable delay
- **Add preference:** `inlineCompletionDebounceMs` (default 500-1000ms)

### Phase 2: Multi-Suggestion Support

#### 2.1 Suggestion Cycling
- **Goal:** AI can return multiple suggestions; user cycles with `Alt+[` / `Alt+]`
- **Changes:**
  - `ClaudeCompletionClient.java` — parse multiple suggestions from CLI response
  - `InlineCompletionService.java` — maintain suggestion list, track current index
  - `GhostTextPainter.java` — show current suggestion from list

### Phase 3: Inline Edits (Medium effort, high impact)

#### 3.1 Inline Edit Detection
- **Goal:** When AI suggests replacing existing code (not just appending), show it as an inline edit
- **Changes:**
  - New `InlineEditPainter.java` — renders strikethrough for deletions, green for additions
  - `InlineCompletionService.java` — detect if suggestion is an edit vs append by diffing

#### 3.2 Side-by-Side Diff View
- **Goal:** For multi-line edits, show original vs suggested in a side-by-side overlay
- **Changes:** New `InlineDiffView.java` using Swing `JLayer` or custom painting

### Phase 4: Advanced Features

#### 4.1 Syntax Highlighting in Ghost Text
- **Goal:** Ghost text uses language-appropriate syntax colors instead of plain grey
- **Approach:** Use NetBeans `LexerTokenHierarchy` to tokenize the suggestion, render with syntax colors
- **VS Code equivalent:** `editor.inlineSuggest.syntaxHighlightingEnabled`

#### 4.2 Yield-to NetBeans Completion
- **Goal:** When NetBeans' built-in code completion popup is visible, hide or yield ghost text
- **Changes:** Hook into `Completion.get().showCompletion()` / `JTextComponent` client property checks

#### 4.3 Hover Toolbar (Accept/Reject Buttons)
- **Goal:** Show a small floating toolbar above ghost text with Accept / Reject / Next buttons
- **Changes:** New `GhostTextToolbar.java` — Swing popup near caret position

#### 4.4 Next Edit Suggestions (NES)
- **Goal:** After accepting an edit, AI predicts the next edit location and content
- **Changes:**
  - `ClaudeCompletionClient.java` — new prompt mode for "next edit" prediction
  - New `NextEditHint.java` — gutter marker / underline at predicted location
  - `Tab` at predicted location accepts the edit

### Phase 5: Polish & Configuration

#### 5.1 Preferences Panel
- Add settings for: enabled, debounce delay, font family, mode (prefix/subword), syntax highlighting, show toolbar, min show delay
- Wire to `ClaudeCodePreferences.java`

#### 5.2 Performance
- Cache recent suggestions to avoid re-calling CLI on backspace
- Cancel in-flight requests when cursor moves (already partially done)
- Rate-limit CLI calls (max 1 request per N seconds)

---

## 4. Priority Order

| Priority | Feature | Effort | Impact |
|----------|---------|--------|--------|
| P0 | Partial acceptance (next word/line) | Low | High |
| P0 | Dismiss on cursor move | Low | High |
| P0 | Debounce for all triggers | Low | High |
| P1 | Multi-suggestion cycling | Medium | Medium |
| P1 | Inline edits (deletions/replacements) | Medium | High |
| P2 | Syntax highlighting in ghost text | Medium | Medium |
| P2 | Yield-to NetBeans completion | Low | Medium |
| P2 | Hover toolbar | Medium | Medium |
| P3 | Next Edit Suggestions (NES) | High | High |
| P3 | Side-by-side diff view | High | Medium |

---

## 5. Key Source Files to Reference

| VS Code File | Purpose |
|--------------|---------|
| `src/vs/editor/contrib/inlineCompletions/browser/controller/inlineCompletionsController.ts` | Controller — commands, lifecycle |
| `src/vs/editor/contrib/inlineCompletions/browser/model/inlineCompletionsModel.ts` | Model — state machine, yield logic |
| `src/vs/editor/contrib/inlineCompletions/browser/model/inlineCompletionsSource.ts` | Source — provider fetching, caching |
| `src/vs/editor/contrib/inlineCompletions/browser/model/ghostText.ts` | GhostText data model |
| `src/vs/editor/contrib/inlineCompletions/browser/model/computeGhostText.ts` | Ghost text computation (diffing) |
| `src/vs/editor/contrib/inlineCompletions/browser/model/inlineSuggestionItem.ts` | Item model (completion vs edit) |
| `src/vs/editor/contrib/inlineCompletions/browser/view/inlineEdits/inlineEditsView.ts` | Inline edits rendering |
| `src/vs/editor/contrib/inlineCompletions/browser/controller/commands.ts` | Accept commands (full, next word, next line) |
| `src/vs/editor/contrib/suggest/browser/suggestController.ts` | Standard completion widget (yield coordination) |
| `src/vs/vscode-dts/vscode.proposed.inlineCompletionsAdditions.d.ts` | Proposed API for inline edits |

---

*Research conducted from VS Code GitHub repository (microsoft/vscode) and DeepWiki documentation.*
