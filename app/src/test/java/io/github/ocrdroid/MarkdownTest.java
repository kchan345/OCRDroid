package io.github.ocrdroid;

import org.junit.Test;
import static org.junit.Assert.*;

public class MarkdownTest {
    @Test public void rendersMarkdownAndModelHtmlTables() {
        String html = Markdown.document("# Receipt\n\n**Total** 38.50\n\n<table><tr><td>A</td><td>1</td></tr></table>\n\n"
            + "| Item | Price |\n| --- | --- |\n| Tea | 2.00 |\n", null);
        assertTrue(html, html.contains("<h1>Receipt</h1>"));
        assertTrue(html, html.contains("<strong>Total</strong>"));
        assertTrue(html, html.contains("<table><tr><td>A</td><td>1</td></tr></table>"));
        assertTrue(html, html.contains("<td>Tea</td>"));
        assertTrue(html, html.contains("Content-Security-Policy"));
        assertTrue(html, html.contains("default-src 'none'"));
    }

    @Test public void figureTagsBecomeLocalCropsOrPlaceholders() {
        String source = "Intro\n\n<img src=\"images/bbox_10_20_500_600.jpg\" />\n\nEnd";
        int[] seen = new int[4];
        String cropped = Markdown.document(source, (l, t, r, b) -> {
            seen[0] = l; seen[1] = t; seen[2] = r; seen[3] = b;
            return "data:image/jpeg;base64,QUJD";
        });
        assertArrayEquals(new int[]{10, 20, 500, 600}, seen);
        assertTrue(cropped, cropped.contains("src=\"data:image/jpeg;base64,QUJD\""));
        assertFalse(cropped, cropped.contains("images/bbox_"));
        String placeholder = Markdown.document(source, (l, t, r, b) -> null);
        assertTrue(placeholder, placeholder.contains("[Image region]"));
        assertFalse(placeholder, placeholder.contains("images/bbox_"));
        String rejected = Markdown.document(source, (l, t, r, b) -> "https://example.com/x.jpg");
        assertFalse(rejected, rejected.contains("example.com"));
    }

    @Test public void unsafeLinksAreSanitized() {
        String html = Markdown.document("[click](javascript:alert(1))", null);
        assertFalse(html, html.contains("javascript:"));
    }
}
