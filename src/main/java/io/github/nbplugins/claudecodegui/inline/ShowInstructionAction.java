package io.github.nbplugins.claudecodegui.inline;

import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import javax.swing.text.JTextComponent;
import org.netbeans.api.editor.EditorRegistry;

/**
 * Action bound to <b>Ctrl+I</b> that shows the floating "Enter instruction" popup
 * anchored below the caret in the currently focused editor.
 *
 * <p>Registered in {@code layer.xml} under {@code Editors/Actions}.
 * Assign the shortcut via Tools → Keyboard Shortcuts if not already set.
 */
public final class ShowInstructionAction extends AbstractAction {

    public ShowInstructionAction() {
        super("Show Inline AI Instruction Dialog");
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        JTextComponent editor = EditorRegistry.lastFocusedComponent();
        if (editor == null) return;
        InlineInstructionDialog.show(editor);
    }
}
