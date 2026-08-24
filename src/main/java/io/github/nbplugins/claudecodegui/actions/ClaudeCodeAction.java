package io.github.nbplugins.claudecodegui.actions;

import io.github.nbplugins.claudecodegui.settings.ClaudeCodePreferences;
import io.github.nbplugins.claudecodegui.ui.ClaudeSessionTab;
import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;
import org.openide.util.ImageUtilities;
import org.openide.util.NbBundle.Messages;

/**
 * Toolbar action that opens the Claude Code chat window.
 *
 * <p>Registered in the Build toolbar via {@code layer.xml}.
 */
@ActionID(
    category = "Window",
    id = "io.github.nbplugins.claudecodegui.actions.ClaudeCodeAction"
)
@ActionRegistration(
    displayName = "#CTL_ClaudeCodeAction",
    lazy = false,
    asynchronous = false
)
@ActionReferences({
    @ActionReference(path = "Toolbars/Build", position = 200),
    @ActionReference(path = "Menu/Tools", position = 950)
})
@Messages("CTL_ClaudeCodeAction=Claude Code")
public final class ClaudeCodeAction extends AbstractAction {

    private static final String ICON_CLAUDE           = "io/github/nbplugins/claudecodegui/icons/claude-icon.png";
    private static final String ICON_CLAUDE_32        = "io/github/nbplugins/claudecodegui/icons/claude-icon-32.png";
    private static final String ICON_DEVIN            = "io/github/nbplugins/claudecodegui/icons/devin-icon.png";
    private static final String ICON_DEVIN_32         = "io/github/nbplugins/claudecodegui/icons/devin-icon-32.png";
    private static final String ICON_ANTIGRAVITY      = "io/github/nbplugins/claudecodegui/icons/antigravity-icon.png";
    private static final String ICON_ANTIGRAVITY_32   = "io/github/nbplugins/claudecodegui/icons/antigravity-icon-32.png";
    private static final String ICON_CURSOR           = "io/github/nbplugins/claudecodegui/icons/cursor-icon.png";
    private static final String ICON_CURSOR_32        = "io/github/nbplugins/claudecodegui/icons/cursor-icon-32.png";

    /** Constructs the action and sets the toolbar icon based on the configured CLI type. */
    public ClaudeCodeAction() {
        String iconPath;
        String iconPath32;
        String label;
        if (ClaudeCodePreferences.isDevinCli()) {
            iconPath   = ICON_DEVIN;
            iconPath32 = ICON_DEVIN_32;
            label      = "Devin";
        } else if (ClaudeCodePreferences.isAntigravityCli()) {
            iconPath   = ICON_ANTIGRAVITY;
            iconPath32 = ICON_ANTIGRAVITY_32;
            label      = "Google Antigravity";
        } else if (ClaudeCodePreferences.isCursorCli()) {
            iconPath   = ICON_CURSOR;
            iconPath32 = ICON_CURSOR_32;
            label      = "Cursor";
        } else if (ClaudeCodePreferences.isCodexCli()) {
            iconPath   = ICON_CLAUDE;
            iconPath32 = ICON_CLAUDE_32;
            label      = "OpenAI Codex";
        } else {
            iconPath   = ICON_CLAUDE;
            iconPath32 = ICON_CLAUDE_32;
            label      = "Claude Code";
        }
        putValue("iconBase", iconPath32);        // 32px base — NetBeans toolbar scales down crisply
        putValue(SMALL_ICON, ImageUtilities.loadImageIcon(iconPath, false));
        putValue(LARGE_ICON_KEY, ImageUtilities.loadImageIcon(iconPath32, false));
        putValue(SHORT_DESCRIPTION, label);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        ClaudeSessionTab.openNewOrFocus();
    }
}
