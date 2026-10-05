package io.github.ocrdroid;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class TextAnchorsTest {
    private final TextAnchors.Box box = new TextAnchors.Box(10, 20, 80, 40);

    @Test public void matchesMarkdownCaseAndLineWhitespace() {
        var anchors = TextAnchors.align("**HELLO**\nworld", List.of(new TextAnchors.Region("Hello world", box)));
        assertEquals(2, anchors.size());
        assertSame(box, TextAnchors.selected(anchors, 2, 7).get(0));
        assertEquals(1, TextAnchors.selected(anchors, 0, 15).size());
    }

    @Test public void ambiguousRepeatedLinesStayUnmapped() {
        var anchors = TextAnchors.align("total total", List.of(new TextAnchors.Region("total", box)));
        assertTrue(anchors.isEmpty());
    }

    @Test public void lineContextDisambiguatesRepeatedWords() {
        var second = new TextAnchors.Box(10, 50, 80, 70);
        var anchors = TextAnchors.align("Total 12\nTotal 34", List.of(
            new TextAnchors.Region("Total 12", box), new TextAnchors.Region("Total 34", second)));
        assertSame(second, TextAnchors.selected(anchors, 9, 14).get(0));
    }

    @Test public void unmatchedTextNeverGetsGuessedBox() {
        assertTrue(TextAnchors.align("invented", List.of(new TextAnchors.Region("actual", box))).isEmpty());
    }

    @Test public void editsInvalidateTouchedWordsAndShiftLaterSpans() {
        var anchors = List.of(new TextAnchors.Anchor(0, 5, box), new TextAnchors.Anchor(10, 15, box));
        var changed = TextAnchors.edit(anchors, 2, 1, 3);
        assertEquals(1, changed.size());
        assertEquals(12, changed.get(0).start);
        assertEquals(17, changed.get(0).end);
    }

    @Test public void insertedSuffixOrPrefixInvalidatesOriginalWord() {
        var anchors = List.of(new TextAnchors.Anchor(2, 5, box));
        assertTrue(TextAnchors.edit(anchors, 5, 0, 1).isEmpty());
        assertTrue(TextAnchors.edit(anchors, 2, 0, 1).isEmpty());
    }

    @Test public void unicodeOffsetsUseUtf16() {
        String text = "\uD83D\uDCF7 \u4F60\u597D";
        var anchors = TextAnchors.align(text, List.of(new TextAnchors.Region("\u4F60\u597D", box)));
        assertEquals(3, anchors.get(0).start);
        assertEquals(5, anchors.get(0).end);
    }

    @Test public void caretDoesNotHighlight() {
        assertTrue(TextAnchors.selected(List.of(new TextAnchors.Anchor(0, 5, box)), 2, 2).isEmpty());
    }

    @Test public void htmlMetadataIsNotGroundedAsDocumentText() {
        String text = "<img src=\"images/bbox_10_20_80_40.jpg\" />\nInvoice 10";
        var anchors = TextAnchors.align(text, List.of(new TextAnchors.Region("Invoice 10", box)));
        assertTrue(TextAnchors.selected(anchors, 0, 39).isEmpty());
        int invoice = text.indexOf("Invoice");
        assertEquals(1, TextAnchors.selected(anchors, invoice, text.length()).size());
    }

    @Test public void conflictingLineMatchesDoNotInventWordAssociations() {
        var second = new TextAnchors.Box(10, 50, 80, 70);
        var anchors = TextAnchors.align("Alpha Beta Gamma", List.of(
            new TextAnchors.Region("Alpha Beta", box), new TextAnchors.Region("Beta Gamma", second)));
        assertTrue(TextAnchors.selected(anchors, 6, 10).isEmpty());
        assertFalse(TextAnchors.selected(anchors, 0, 5).isEmpty());
    }
}
