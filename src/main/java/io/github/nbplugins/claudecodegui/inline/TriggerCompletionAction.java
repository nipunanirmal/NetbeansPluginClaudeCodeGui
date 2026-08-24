package io.github.nbplugins.claudecodegui.inline;

import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import javax.swing.text.JTextComponent;
import org.netbeans.api.editor.EditorRegistry;

/**
 * Action bound to <b>Alt+\</b> that triggers an inline AI completion request
 * immediately at the current caret position, bypassing the debounce timer.
 *
 * <p>Registered in {@code layer.xml} under {@code Editors/Actions}.
 */
public final class TriggerCompletionAction extends AbstractAction {

    public TriggerCompletionAction() {
        super("Trigger Inline AI Completion");
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        JTextComponent editor = EditorRegistry.lastFocusedComponent();
        if (editor == null) return;
        InlineCompletionService svc = InlineCompletionService.getInstance();
        if (svc != null) {
            svc.triggerNow();
        }
    }
}
