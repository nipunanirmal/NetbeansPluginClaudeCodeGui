package io.github.nbplugins.claudecodegui.inline;

import io.github.nbplugins.claudecodegui.settings.ClaudeCodePreferences;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import org.netbeans.api.editor.EditorRegistry;

/**
 * Singleton service that wires inline AI ghost-text completion into the active
 * NetBeans source editor.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Started via {@link #start()} from {@code ClaudeCodeInstaller.restored()}.</li>
 *   <li>Registers an {@link EditorRegistry} change listener to track editor focus.</li>
 *   <li>When the focused editor changes, installs a {@link DocumentListener} and
 *       optionally a debounce {@link Timer} on the new component.</li>
 *   <li>After the debounce delay (or on explicit {@link #triggerNow()} call),
 *       spawns the CLI via {@link ClaudeCompletionClient} and paints the result
 *       via {@link GhostTextPainter}.</li>
 *   <li>A {@link KeyListener} (Tab = accept, any other key = dismiss) is installed
 *       on the editor only while ghost text is visible.</li>
 * </ol>
 *
 * <p>All Swing operations are performed on the EDT; the CLI call runs on a
 * background thread managed by {@link CompletableFuture}.
 */
public final class InlineCompletionService {

    private static final Logger LOG = Logger.getLogger(InlineCompletionService.class.getName());

    private static InlineCompletionService instance;

    private final GhostTextPainter painter = new GhostTextPainter();

    private JTextComponent currentEditor;
    private Timer debounceTimer;
    private CompletableFuture<String> pendingFuture;

    private final DocumentListener docListener = new DocumentListener() {
        @Override
        public void insertUpdate(DocumentEvent e) {
            // Capture document snapshot while still on the document-mutation thread,
            // then hand off to EDT for all Swing/timer work.
            int offset = e.getOffset() + e.getLength();
            String text = snapshotText(e);
            SwingUtilities.invokeLater(() -> onDocumentChanged(text, offset));
        }
        @Override
        public void removeUpdate(DocumentEvent e) {
            int offset = e.getOffset();
            String text = snapshotText(e);
            SwingUtilities.invokeLater(() -> onDocumentChanged(text, offset));
        }
        @Override public void changedUpdate(DocumentEvent e) { /* attribute change — ignore */ }

        private String snapshotText(DocumentEvent e) {
            try {
                return e.getDocument().getText(0, e.getDocument().getLength());
            } catch (Exception ex) {
                return null;
            }
        }
    };

    private KeyListener ghostKeyListener;

    /**
     * Returns the singleton instance created by the NetBeans Lookup, or
     * {@code null} if the service has not been initialised yet.
     *
     * @return the service instance, or {@code null}
     */
    public static InlineCompletionService getInstance() {
        return instance;
    }

    /**
     * Creates and starts the singleton {@link InlineCompletionService}.
     * Call from {@code ClaudeCodeInstaller.restored()} on the EDT (or wrapped in
     * {@code WindowManager.getDefault().invokeWhenUIReady}).
     */
    public static void start() {
        if (instance != null) return;
        InlineCompletionService svc = new InlineCompletionService();
        instance = svc;
        // Listen for editor focus events — use getNewValue() for FOCUS_GAINED_PROPERTY
        // which carries the newly-focused JTextComponent directly and is reliable.
        EditorRegistry.addPropertyChangeListener(evt -> {
            Object nv = evt.getNewValue();
            if (nv instanceof JTextComponent) {
                // FOCUS_GAINED_PROPERTY — attach directly from the event
                SwingUtilities.invokeLater(() -> svc.onEditorGainedFocus((JTextComponent) nv));
            } else {
                // Other property changes (FOCUSED_DOCUMENT etc.) — use fallback
                SwingUtilities.invokeLater(svc::onEditorFocusChanged);
            }
        });
        // Attach to any editors already open when the service starts
        SwingUtilities.invokeLater(() -> {
            for (JTextComponent tc : EditorRegistry.componentList()) {
                svc.attachToEditor(tc);
            }
            JTextComponent last = EditorRegistry.lastFocusedComponent();
            if (last != null) {
                svc.currentEditor = last;
                LOG.info("InlineCompletionService primed with editor: "
                        + last.getClass().getSimpleName());
            }
        });
        LOG.info("InlineCompletionService started");
    }

    /**
     * Triggers a completion request immediately at the current caret position,
     * bypassing the debounce timer.  Called by {@link TriggerCompletionAction}.
     */
    public void triggerNow() {
        if (!ClaudeCodePreferences.isInlineCompletionEnabled()) return;
        JTextComponent editor = EditorRegistry.lastFocusedComponent();
        if (editor == null) editor = currentEditor;
        if (editor == null) return;
        final JTextComponent tc = editor;
        String text;
        int offset;
        try {
            text   = tc.getDocument().getText(0, tc.getDocument().getLength());
            offset = tc.getCaretPosition();
        } catch (Exception ex) {
            return;
        }
        cancelPending(tc);
        requestCompletion(tc, text, offset, isCommentTrigger(text, offset));
    }

    // -------------------------------------------------------------------------
    // Editor focus tracking
    // -------------------------------------------------------------------------

    /** Called with the component from FOCUS_GAINED_PROPERTY event — most reliable path. */
    private void onEditorGainedFocus(JTextComponent tc) {
        if (tc != currentEditor) {
            currentEditor = tc;
            attachToEditor(tc);
            LOG.info("InlineCompletionService: editor focused " + tc.getClass().getSimpleName());
        }
    }

    /** Fallback for non-FOCUS_GAINED events. */
    private void onEditorFocusChanged() {
        JTextComponent focused = EditorRegistry.focusedComponent();
        if (focused == null) focused = EditorRegistry.lastFocusedComponent();
        if (focused != null && focused != currentEditor) {
            currentEditor = focused;
            attachToEditor(focused);
        }
    }

    private void attachToEditor(JTextComponent tc) {
        // Remove first to ensure we never double-add the same listener
        tc.getDocument().removeDocumentListener(docListener);
        tc.getDocument().addDocumentListener(docListener);
        LOG.info("InlineCompletionService attached to editor: " + tc.getClass().getSimpleName());
    }

    private void detachFromEditor(JTextComponent tc) {
        tc.getDocument().removeDocumentListener(docListener);
        cancelPending(tc);
        painter.hide(tc);
        removeGhostKeyListener(tc);
        LOG.fine("InlineCompletionService detached from editor");
    }

    // -------------------------------------------------------------------------
    // Document change → debounce → request
    // -------------------------------------------------------------------------

    /** Called on EDT with a document snapshot taken at mutation time. */
    private void onDocumentChanged(String docSnapshot, int changeOffset) {
        if (!ClaudeCodePreferences.isInlineCompletionEnabled()) return;

        // Resolve the active editor — fall back through multiple strategies
        JTextComponent editor = EditorRegistry.focusedComponent();
        if (editor == null) editor = EditorRegistry.lastFocusedComponent();
        if (editor == null) editor = currentEditor;
        if (editor == null) return;

        if (editor != currentEditor) {
            currentEditor = editor;
            attachToEditor(editor);
        }

        final JTextComponent finalEditor = editor;
        final String finalText = docSnapshot != null ? docSnapshot : "";
        final int finalOffset = changeOffset;

        cancelPending(finalEditor);

        // Only trigger on // comment lines, not on regular typing
        boolean isCommentTrigger = isCommentTrigger(finalText, finalOffset);
        if (!isCommentTrigger) {
            return;
        }

        int delayMs = 2000; // Fixed delay for comment triggers (prevents API spam)

        LOG.info("InlineCompletion: scheduling in " + delayMs + "ms for comment trigger");

        if (debounceTimer != null && debounceTimer.isRunning()) {
            debounceTimer.stop();
        }
        debounceTimer = new Timer(delayMs, e -> {
            debounceTimer = null;
            requestCompletion(finalEditor, finalText, finalOffset, isCommentTrigger);
        });
        debounceTimer.setRepeats(false);
        debounceTimer.start();
    }

    /**
     * Returns {@code true} when the text at {@code offset} is on a line whose
     * non-whitespace content starts with {@code //} and has some text after it
     * (i.e. the developer has typed an instruction, not just {@code //}).
     */
    private static boolean isCommentTrigger(String text, int offset) {
        if (text == null || offset <= 0) return false;
        try {
            int safeOffset = Math.min(offset, text.length());
            int lineStart = text.lastIndexOf('\n', safeOffset - 1) + 1;
            String linePrefix = text.substring(lineStart, safeOffset).stripLeading();
            // Must start with // and have at least one non-slash character
            return linePrefix.startsWith("//") && linePrefix.length() > 2
                    && !linePrefix.substring(2).isBlank();
        } catch (Exception ex) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Completion request + ghost text display
    // -------------------------------------------------------------------------

    private void requestCompletion(JTextComponent tc, String text, int caretOffset,
                                    boolean commentTrigger) {
        LOG.info("InlineCompletion: requesting from CLI at offset=" + caretOffset
                + " comment=" + commentTrigger);
        // Capture the caret position at request time; the user may move it while
        // the CLI call is in flight.
        final int requestedOffset = caretOffset;
        pendingFuture = ClaudeCompletionClient.requestCompletion(text, caretOffset, commentTrigger);
        pendingFuture.thenAccept(suggestion -> SwingUtilities.invokeLater(() -> {
            if (suggestion == null || suggestion.isEmpty()) {
                LOG.info("InlineCompletion: empty/null response from CLI");
                return;
            }
            LOG.info("InlineCompletion: showing ghost text length=" + suggestion.length());
            // Show on the editor the request was made for regardless of currentEditor —
            // focus may have shifted to a helper pane (e.g. JEditorPane during indexing)
            // while the CLI was running, which previously caused the guard to fire.
            showGhostText(tc, requestedOffset, suggestion);
        }));
    }

    private void showGhostText(JTextComponent tc, int caretOffset, String suggestion) {
        painter.show(tc, caretOffset, suggestion);
        installGhostKeyListener(tc);
        tc.repaint();
    }

    // -------------------------------------------------------------------------
    // Tab-accept / dismiss key listener
    // -------------------------------------------------------------------------

    private void installGhostKeyListener(JTextComponent tc) {
        removeGhostKeyListener(tc);
        ghostKeyListener = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!painter.isShowing()) {
                    removeGhostKeyListener(tc);
                    return;
                }
                if (e.getKeyCode() == KeyEvent.VK_TAB) {
                    e.consume();
                    acceptSuggestion(tc);
                } else {
                    dismissGhostText(tc);
                }
            }
        };
        tc.addKeyListener(ghostKeyListener);
    }

    private void removeGhostKeyListener(JTextComponent tc) {
        if (ghostKeyListener != null) {
            tc.removeKeyListener(ghostKeyListener);
            ghostKeyListener = null;
        }
    }

    private void acceptSuggestion(JTextComponent tc) {
        String suggestion = painter.getSuggestionText();
        painter.hide(tc);
        removeGhostKeyListener(tc);
        if (suggestion == null || suggestion.isEmpty()) return;
        try {
            int caret = tc.getCaretPosition();
            String text = tc.getDocument().getText(0, tc.getDocument().getLength());
            // Insert at the beginning of the next line after the caret/comment line.
            // This places the generated code below the // comment instead of
            // mid-line at the cursor position.
            int nextLine = text.indexOf('\n', caret);
            int offset = (nextLine >= 0) ? nextLine + 1 : caret;
            tc.getDocument().insertString(offset, suggestion, null);
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Could not insert suggestion", ex);
        }
    }

    private void dismissGhostText(JTextComponent tc) {
        painter.hide(tc);
        removeGhostKeyListener(tc);
        tc.repaint();
    }

    // -------------------------------------------------------------------------
    // Cancel helpers
    // -------------------------------------------------------------------------

    private void cancelPending(JTextComponent tc) {
        if (debounceTimer != null) {
            debounceTimer.stop();
            debounceTimer = null;
        }
        if (pendingFuture != null && !pendingFuture.isDone()) {
            pendingFuture.cancel(true);
            pendingFuture = null;
        }
        if (painter.isShowing()) {
            dismissGhostText(tc);
        }
    }
}
