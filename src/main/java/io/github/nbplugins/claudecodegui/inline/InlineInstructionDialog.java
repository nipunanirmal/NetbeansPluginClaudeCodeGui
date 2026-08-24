package io.github.nbplugins.claudecodegui.inline;

import io.github.nbplugins.claudecodegui.settings.ClaudeCodePreferences;
import io.github.nbplugins.claudecodegui.settings.ClaudeProfileStore;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Rectangle2D;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

/**
 * Floating "Enter instruction" popup triggered by Ctrl+I.
 *
 * <p>Displays a compact dialog anchored below the caret containing a text field
 * (the instruction) and a model/profile selector.  On Submit (Enter or button click),
 * calls the AI CLI with an instruction-style prompt and inserts the result at the
 * caret position, replacing any selected text.
 *
 * <p>Dismiss with Escape or click outside.
 */
public final class InlineInstructionDialog {

    private static final Logger LOG = Logger.getLogger(InlineInstructionDialog.class.getName());

    /** Width of the instruction input dialog in pixels. */
    private static final int DIALOG_WIDTH  = 460;
    private static final int DIALOG_HEIGHT = 60;

    private InlineInstructionDialog() {}

    /**
     * Shows the instruction dialog anchored below the caret in the given editor.
     * If the editor has selected text, that selection is replaced by the AI output.
     *
     * @param textComponent the source editor component
     */
    public static void show(JTextComponent textComponent) {
        if (textComponent == null) return;
        Point location = getCaretScreenLocation(textComponent);
        if (location == null) {
            location = textComponent.getLocationOnScreen();
        }
        showAt(textComponent, location);
    }

    private static void showAt(JTextComponent tc, Point screenLocation) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(tc));
        dialog.setUndecorated(true);
        dialog.setModal(false);

        JPanel content = new JPanel(new BorderLayout(6, 0));
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x50, 0x50, 0x50), 1),
                BorderFactory.createEmptyBorder(6, 10, 6, 8)));
        content.setBackground(new Color(0x2b, 0x2b, 0x2b));

        // Profile selector
        JComboBox<String> profileCombo = buildProfileCombo();
        profileCombo.setFont(profileCombo.getFont().deriveFont(11f));
        profileCombo.setPreferredSize(new Dimension(130, 26));

        // Instruction input field
        JTextField inputField = new JTextField();
        inputField.setFont(inputField.getFont().deriveFont(Font.PLAIN, 13f));
        inputField.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        inputField.setBackground(new Color(0x2b, 0x2b, 0x2b));
        inputField.setForeground(Color.LIGHT_GRAY);
        inputField.setCaretColor(Color.LIGHT_GRAY);

        // Submit button
        JButton submitBtn = new JButton("Submit");
        submitBtn.setFont(submitBtn.getFont().deriveFont(Font.BOLD, 12f));
        submitBtn.setFocusPainted(false);
        submitBtn.setBackground(new Color(0x26, 0x5c, 0x9e));
        submitBtn.setForeground(Color.WHITE);
        submitBtn.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        submitBtn.setPreferredSize(new Dimension(100, 28));

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.add(profileCombo);
        right.add(Box.createHorizontalStrut(6));
        right.add(submitBtn);

        content.add(inputField, BorderLayout.CENTER);
        content.add(right, BorderLayout.EAST);

        // ESC closes
        inputField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        inputField.getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { dialog.dispose(); }
        });

        // Enter submits
        Runnable submit = () -> {
            String instruction = inputField.getText().trim();
            if (instruction.isEmpty()) { dialog.dispose(); return; }
            String profileName = profileCombo.getSelectedIndex() == 0 ? null
                    : (String) profileCombo.getSelectedItem();
            dialog.dispose();
            runInstruction(tc, instruction, profileName);
        };

        inputField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "submit");
        inputField.getActionMap().put("submit", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { submit.run(); }
        });
        submitBtn.addActionListener(e -> submit.run());

        // Close when dialog loses focus
        dialog.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) { dialog.dispose(); }
        });

        dialog.setContentPane(content);
        dialog.setSize(DIALOG_WIDTH, DIALOG_HEIGHT);
        dialog.setLocation(screenLocation.x, screenLocation.y + 4);
        dialog.setVisible(true);
        inputField.requestFocusInWindow();
    }

    /**
     * Sends the instruction + surrounding code context to the CLI and inserts the
     * result at the caret (replacing selection if any).
     */
    private static void runInstruction(JTextComponent tc, String instruction, String profileName) {
        String text;
        int selStart, selEnd;
        try {
            text     = tc.getDocument().getText(0, tc.getDocument().getLength());
            selStart = tc.getSelectionStart();
            selEnd   = tc.getSelectionEnd();
        } catch (Exception ex) {
            LOG.log(Level.FINE, "InlineInstruction: could not read editor", ex);
            return;
        }

        int caretOffset = selEnd;
        String prompt   = buildInstructionPrompt(text, selStart, selEnd, instruction);

        // Temporarily override profile if the user selected a non-default one
        CompletableFuture<String> future = ClaudeCompletionClient.requestWithPrompt(prompt, profileName);
        future.thenAccept(result -> SwingUtilities.invokeLater(() -> {
            if (result == null || result.isEmpty()) return;
            try {
                if (selStart != selEnd) {
                    tc.getDocument().remove(selStart, selEnd - selStart);
                    tc.getDocument().insertString(selStart, result, null);
                } else {
                    tc.getDocument().insertString(caretOffset, "\n" + result, null);
                }
            } catch (Exception ex) {
                LOG.log(Level.FINE, "InlineInstruction: could not insert result", ex);
            }
        }));
    }

    /**
     * Builds the instruction prompt including the surrounding code context.
     */
    private static String buildInstructionPrompt(String doc, int selStart, int selEnd,
                                                  String instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a code assistant. ");
        sb.append("Implement the following instruction and output ONLY the resulting code. ");
        sb.append("No explanations, no markdown fences.\n\n");
        sb.append("Instruction: ").append(instruction).append("\n\n");

        // Include selected text as "code to modify" if any
        if (selStart < selEnd) {
            sb.append("Selected code to modify:\n");
            sb.append(doc, selStart, selEnd).append("\n\n");
        }

        // Surrounding context
        String before = doc.substring(0, selStart);
        String[] beforeLines = before.split("\n", -1);
        int start = Math.max(0, beforeLines.length - 30);
        sb.append("Surrounding context (before):\n");
        for (int i = start; i < beforeLines.length; i++) {
            sb.append(beforeLines[i]).append('\n');
        }

        return sb.toString();
    }

    /**
     * Builds the profile combo box populated with "Default (current session)" plus
     * all named profiles.
     */
    private static JComboBox<String> buildProfileCombo() {
        JComboBox<String> combo = new JComboBox<>();
        combo.addItem("Default (current session)");
        for (io.github.nbplugins.claudecodegui.settings.ClaudeProfile p
                : ClaudeProfileStore.getProfiles()) {
            if (!p.isDefault()) combo.addItem(p.getName());
        }
        String override = ClaudeCodePreferences.getInlineProfileOverride();
        if (override != null && !override.isBlank()) {
            for (int i = 0; i < combo.getItemCount(); i++) {
                if (override.equals(combo.getItemAt(i))) {
                    combo.setSelectedIndex(i);
                    break;
                }
            }
        }
        return combo;
    }

    /**
     * Returns the screen coordinates just below the caret in the given component.
     */
    private static Point getCaretScreenLocation(JTextComponent tc) {
        try {
            int caret = tc.getCaretPosition();
            Rectangle2D r = tc.modelToView2D(caret);
            if (r == null) return null;
            Point p = new Point((int) r.getX(), (int) (r.getY() + r.getHeight()));
            SwingUtilities.convertPointToScreen(p, tc);
            return p;
        } catch (BadLocationException ex) {
            return null;
        }
    }
}
