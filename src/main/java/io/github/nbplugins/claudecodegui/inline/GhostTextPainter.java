package io.github.nbplugins.claudecodegui.inline;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.View;

/**
 * Renders inline AI ghost-text suggestions as light grey italic overlay text
 * in a {@link JTextComponent}, using the Swing {@link Highlighter} API.
 *
 * <p>A single instance is reused for the lifetime of the
 * {@link InlineCompletionService}.  Call {@link #show} to display a suggestion
 * and {@link #hide} to dismiss it.  The highlight tag is stored internally;
 * multiple calls to {@link #show} replace any existing highlight.
 *
 * <p>All methods must be called on the Event Dispatch Thread.
 */
public final class GhostTextPainter implements Highlighter.HighlightPainter {

    private static final Logger LOG = Logger.getLogger(GhostTextPainter.class.getName());
    private static final Color GHOST_COLOR = new Color(0x88, 0x88, 0x88);

    private String suggestionText = "";
    private Object highlightTag   = null;

    /**
     * Displays the suggestion as ghost text starting at the given caret offset.
     *
     * @param textComponent the editor component
     * @param caretOffset   0-based caret position; ghost text is rendered here
     * @param suggestion    the text to display (may be multi-line)
     */
    public void show(JTextComponent textComponent, int caretOffset, String suggestion) {
        if (suggestion == null || suggestion.isEmpty()) return;
        hide(textComponent);
        this.suggestionText = suggestion;
        try {
            int safeOffset = Math.max(0, Math.min(caretOffset, textComponent.getDocument().getLength()));
            highlightTag = textComponent.getHighlighter().addHighlight(safeOffset, safeOffset, this);
        } catch (BadLocationException ex) {
            LOG.log(Level.FINE, "GhostTextPainter: bad caret offset", ex);
            suggestionText = "";
        }
    }

    /**
     * Removes the ghost-text highlight from the component.
     *
     * @param textComponent the editor component
     */
    public void hide(JTextComponent textComponent) {
        if (highlightTag != null) {
            try {
                textComponent.getHighlighter().removeHighlight(highlightTag);
            } catch (Exception ex) {
                LOG.log(Level.FINEST, "GhostTextPainter: removeHighlight error", ex);
            }
            highlightTag   = null;
            suggestionText = "";
        }
    }

    /**
     * Returns {@code true} if ghost text is currently displayed.
     *
     * @return {@code true} when a suggestion is showing
     */
    public boolean isShowing() {
        return highlightTag != null && !suggestionText.isEmpty();
    }

    /**
     * Returns the suggestion text currently being displayed.
     *
     * @return the pending suggestion, or {@code ""}
     */
    public String getSuggestionText() {
        return suggestionText;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Paints the ghost text in grey italic font at the position of the
     * zero-length highlight.  The first line of the suggestion is drawn on the
     * same line as the caret; subsequent lines are drawn below it.
     */
    @Override
    public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent tc) {
        if (suggestionText == null || suggestionText.isEmpty()) return;
        try {
            Rectangle2D caretRect = tc.modelToView2D(p0);
            if (caretRect == null) return;

            Font baseFont  = tc.getFont();
            Font ghostFont = baseFont.deriveFont(Font.ITALIC);
            g.setFont(ghostFont);
            g.setColor(GHOST_COLOR);

            FontMetrics fm   = g.getFontMetrics(ghostFont);
            int lineHeight   = fm.getHeight();
            String[] lines   = suggestionText.split("\n", -1);
            int x            = (int) caretRect.getX();
            int y            = (int) caretRect.getY() + fm.getAscent();

            for (String line : lines) {
                g.drawString(line, x, y);
                y += lineHeight;
                x  = getLeftMargin(tc, p0);
            }
        } catch (BadLocationException ex) {
            LOG.log(Level.FINEST, "GhostTextPainter: paint error", ex);
        }
    }

    /**
     * Returns the x-coordinate of the left text margin for the given caret position.
     * Used when rendering continuation lines of a multi-line suggestion.
     */
    private static int getLeftMargin(JTextComponent tc, int offset) {
        try {
            View rootView = tc.getUI().getRootView(tc);
            if (rootView != null) {
                Rectangle2D lineStart = tc.modelToView2D(
                        tc.getDocument().getText(0, offset).lastIndexOf('\n') + 1);
                if (lineStart != null) return (int) lineStart.getX();
            }
        } catch (Exception ex) {
            // ignore — fall back to 0
        }
        return 0;
    }
}
