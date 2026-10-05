package io.github.ocrdroid;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** UTF-16 offsets match Android's editable text and selection APIs. */
public final class TextAnchors {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:[.'\\u2019,-][\\p{L}\\p{N}]+)*");
    private static final Pattern HTML_TAG = Pattern.compile("</?[A-Za-z][^>]*>");

    public static final class Box {
        public final float left, top, right, bottom;
        public Box(float left, float top, float right, float bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
    }

    public static final class Region {
        public final String text;
        public final Box box;
        public Region(String text, Box box) { this.text = text; this.box = box; }
    }

    public static final class Anchor {
        public final int start, end;
        public final Box box;
        public Anchor(int start, int end, Box box) {
            this.start = start; this.end = end; this.box = box;
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    public static List<Anchor> align(String text, List<Region> regions) {
        Map<String, List<Anchor>> output = new HashMap<>();
        List<String> keys = new ArrayList<>();
        List<Anchor> spans = new ArrayList<>();
        List<int[]> markup = new ArrayList<>();
        Matcher tags = HTML_TAG.matcher(text);
        while (tags.find()) markup.add(new int[]{tags.start(), tags.end()});
        Matcher words = WORD.matcher(text);
        while (words.find()) {
            boolean inTag = false;
            for (int[] tag : markup) {
                if (words.start() >= tag[0] && words.end() <= tag[1]) { inTag = true; break; }
            }
            if (inTag) continue;
            String key = normalize(words.group());
            Anchor span = new Anchor(words.start(), words.end(), null);
            output.computeIfAbsent(key, ignored -> new ArrayList<>()).add(span);
            keys.add(key);
            spans.add(span);
        }
        Map<String, List<Box>> source = new HashMap<>();
        Map<List<String>, List<Box>> lines = new HashMap<>();
        for (Region region : regions) {
            Matcher tokens = WORD.matcher(region.text);
            List<String> line = new ArrayList<>();
            while (tokens.find()) {
                String key = normalize(tokens.group());
                source.computeIfAbsent(key, ignored -> new ArrayList<>()).add(region.box);
                line.add(key);
            }
            if (!line.isEmpty()) lines.computeIfAbsent(line, ignored -> new ArrayList<>()).add(region.box);
        }
        List<Anchor> anchors = new ArrayList<>();
        java.util.Set<Integer> grounded = new java.util.HashSet<>();
        for (Map.Entry<List<String>, List<Box>> line : lines.entrySet()) {
            if (line.getValue().size() != 1) continue;
            List<String> needle = line.getKey();
            int match = -1;
            for (int i = 0; i + needle.size() <= keys.size(); i++) {
                if (keys.subList(i, i + needle.size()).equals(needle)) {
                    if (match >= 0) { match = -1; break; }
                    match = i;
                }
            }
            if (match < 0) continue;
            for (int i = match; i < match + needle.size(); i++) {
                Anchor span = spans.get(i);
                anchors.add(new Anchor(span.start, span.end, line.getValue().get(0)));
                grounded.add(span.start);
            }
        }
        for (Map.Entry<String, List<Anchor>> entry : output.entrySet()) {
            List<Box> boxes = source.get(entry.getKey());
            // Repeated words are ambiguous across layouts; never invent an occurrence mapping.
            if (boxes == null || boxes.size() != 1 || entry.getValue().size() != 1) continue;
            Anchor span = entry.getValue().get(0);
            if (!grounded.contains(span.start)) anchors.add(new Anchor(span.start, span.end, boxes.get(0)));
        }
        Map<Integer, Integer> counts = new HashMap<>();
        for (Anchor anchor : anchors) counts.merge(anchor.start, 1, Integer::sum);
        anchors.removeIf(anchor -> counts.get(anchor.start) > 1);
        return anchors;
    }

    public static List<Anchor> edit(List<Anchor> anchors, int start, int removed, int inserted) {
        List<Anchor> updated = new ArrayList<>();
        int end = start + removed;
        int delta = inserted - removed;
        for (Anchor a : anchors) {
            if (removed == 0 && inserted == 0) {
                updated.add(a);
            } else if (a.end < start) {
                updated.add(a);
            } else if (a.start > end) {
                updated.add(new Anchor(a.start + delta, a.end + delta, a.box));
            }
            // Any original word touched by an edit loses its source association.
        }
        return updated;
    }

    public static List<Box> selected(List<Anchor> anchors, int start, int end) {
        List<Box> boxes = new ArrayList<>();
        if (start < 0 || end <= start) return boxes;
        for (Anchor a : anchors) {
            if (a.start < end && a.end > start && !boxes.contains(a.box)) boxes.add(a.box);
        }
        return boxes;
    }
}
