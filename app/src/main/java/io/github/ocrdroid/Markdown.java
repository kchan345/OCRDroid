package io.github.ocrdroid;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/** Converts OvisOCR2 Markdown, including its raw HTML tables, into a self-contained offline HTML page. */
public final class Markdown {
    public interface Figures {
        /** Returns a data: URL for a crop in [0, 1000) page coordinates, or null when unavailable. */
        String crop(int left, int top, int right, int bottom);
    }

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).sanitizeUrls(true).build();
    private static final Pattern FIGURE = Pattern.compile(
        "<img\\s[^>]*?src\\s*=\\s*[\"']images/bbox_(\\d{1,4})_(\\d{1,4})_(\\d{1,4})_(\\d{1,4})\\.jpg[\"'][^>]*>",
        Pattern.CASE_INSENSITIVE);
    private static final int MAX_FIGURES = 24;
    private static final String STYLE =
        "body{font-family:sans-serif;font-size:15px;line-height:1.45;color:#1d2321;margin:10px;overflow-wrap:anywhere}"
        + "table{border-collapse:collapse;margin:8px 0;max-width:100%}td,th{border:1px solid #9aa7a2;padding:3px 6px;vertical-align:top}"
        + "th{background:#e7eeeb}pre,code{background:#eef1ef;white-space:pre-wrap}img{max-width:100%}"
        + ".figure{display:inline-block;border:1px dashed #6b7a75;color:#4a5753;padding:4px;margin:4px 0}";

    private Markdown() {}

    public static String document(String markdown, Figures figures) {
        String body = RENDERER.render(PARSER.parse(markdown == null ? "" : markdown));
        Matcher match = FIGURE.matcher(body);
        StringBuffer html = new StringBuffer();
        int count = 0;
        while (match.find()) {
            String url = null;
            if (figures != null && count++ < MAX_FIGURES) {
                url = figures.crop(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                    Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4)));
            }
            String replacement = url == null || !url.startsWith("data:image/")
                ? "<span class=\"figure\">[Image region]</span>"
                : "<img class=\"figure\" alt=\"Image region\" src=\"" + url + "\">";
            match.appendReplacement(html, Matcher.quoteReplacement(replacement));
        }
        match.appendTail(html);
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
            + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<style>" + STYLE + "</style></head><body>" + html + "</body></html>";
    }
}
