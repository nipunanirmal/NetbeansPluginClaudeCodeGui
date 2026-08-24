# Installation & Build

## Requirements

| Requirement | Minimum version |
|-------------|-----------------|
| [Apache NetBeans IDE](https://netbeans.apache.org/front/main/download/) | 23 (RELEASE230) |
| Java | 17 |
| One supported AI CLI (`claude`, `devin`, `agy`, `cursor-agent`, or `codex`) | See the CLI selection guide below |

> **Important:** The embedded terminal requires an installed AI CLI. Choose the AI CLI you want to use in **Tools → Options → Claude Code → Advanced → CLI type**, install that CLI, and ensure its executable is on your `PATH` (or configure its absolute path in **CLI executable path**).

> **Terminal font (recommended):** The plugin auto-selects the best available monospace font. For full Unicode coverage of Claude Code TUI symbols (spinner ◐, prompt marker ⏵, box-drawing characters), install **Adwaita Mono**. On Linux it is typically pre-installed as part of the GNOME desktop. On macOS and Windows, download it from the [GNOME GitLab release page](https://gitlab.gnome.org/GNOME/adwaita-fonts/-/releases). The active font can be changed in **Tools → Options → Claude Code → General**.

The `claude` executable must be on your system `PATH` **or** its absolute path must be configured in **Tools → Options → Claude Code → General → Claude CLI path** after installing the plugin.

---

## Installation

### Recommended: download from GitHub Releases

Download the latest `.nbm` file from the [Releases page](https://github.com/nipunanirmal/NetbeansPlugin-IDE-Devin-ClaudeCode/releases/latest).

### Choose and install an AI CLI

The embedded terminal cannot start until an AI CLI is installed. After installing the plugin, open **Tools → Options → Claude Code → Advanced**, choose the **CLI type** that matches the CLI you installed, and leave **CLI executable path** empty to auto-detect it from `PATH` (or enter the absolute path manually).

| AI / CLI type | Executable |
|---|---|
| Claude Code | `claude` |
| Devin | `devin` |
| Google Antigravity | `agy` |
| Cursor | `cursor-agent` or `agent` |
| OpenAI Codex | `codex` |

The selected CLI must be installed separately using its official installation method. If no CLI is installed, the plugin can still expose NetBeans as an MCP server for another MCP-capable AI client, but the embedded terminal will not launch.

### Intermediate builds

Builds between releases are available as artifacts on the [Actions](https://github.com/nipunanirmal/NetbeansPlugin-IDE-Devin-ClaudeCode/actions) page — open the latest successful workflow run and download the `nbm` artifact (delivered as a zip file; extract the `.nbm` before installing).

### Install into NetBeans

1. Open NetBeans → **Tools → Plugins**
2. Switch to the **Downloaded** tab
3. Click **Add Plugins…** and select the `.nbm` file
4. Click **Install** and follow the wizard
5. Restart NetBeans when prompted

### Uninstall

1. Open NetBeans → **Tools → Plugins**
2. Switch to the **Installed** tab
3. Make sure **Show details** is checked — otherwise only group entries are shown and individual plugins cannot be selected
4. Check the checkbox next to **Claude Code GUI** in the **Select** column
5. Click **Uninstall**
6. Restart NetBeans when prompted

---

## Build from Source

```bash
mvn nbm:nbm
```

The installable plugin file is created at:

```
target/netbeans-claude-code-gui-*.nbm
```

### Other build commands

```bash
mvn package              # Full build with tests
mvn package -DskipTests  # Build without tests
mvn test                 # Run all unit tests
```
