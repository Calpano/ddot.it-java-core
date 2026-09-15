package it.ddot.core.hostreader.asciidoc.doc;

import it.ddot.core.hostreader.asciidoc.doc.AdocDocument;

import java.io.StringWriter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Adocs {

    private static final Pattern PARAMS_PATTERN = Pattern.compile("\\[\\[.*?]]|\\[[^\\[]*?]");

    private static boolean isBlockStart(String line) {
        if (line.startsWith("[[") && line.endsWith("]]")) return true;
        if (line.startsWith("[") && !line.startsWith("[[") && line.endsWith("]")) return true;
        if (line.matches("^={2,6} .+$")) return true;
        if (line.equals("----") || line.equals("....") || line.equals("++++") || line.equals("****")
                || line.equals("////") || line.equals("====") || line.equals("____") || line.equals("--"))
            return true;
        if (line.startsWith(".") && !line.startsWith("..") && line.length() > 1 && !Character.isWhitespace(line.charAt(1)))
            return true;
        return line.startsWith("//");
    }

    /**
     * The fence a line opens, or null when it opens none.
     *
     * <p>The same list the block loop switches on, named once so that the continuation below can
     * ask the question without repeating it.
     */
    private static String fenceOf(String line) {
        return switch (line) {
            case "----", "....", "++++", "****", "////", "====", "____", "--" -> line;
            default -> null;
        };
    }

    /**
     * Whether the lines so far end on a list continuation, which claims whatever comes next.
     *
     * <p>From the AsciiDoc language documentation: "A list continuation is a + symbol on a line by
     * itself, immediately adjacent to the block being attached." So a fence under one is part of the
     * list above it, not a block of the document -- and a parser that cut the file between the two
     * would hand its caller a list, a code block and a second list, with nothing left to say they
     * were ever one thing.
     *
     * <p>A `+` at the end of a line is a hard line break and not this, which is why the test is for
     * a line that is nothing else.
     */
    private static boolean claimsWhatFollows(List<String> lines) {
        return !lines.isEmpty() && lines.getLast().equals("+");
    }

    /** Headlines and section headlines don't need a blank line after them. */
    private static boolean needsBlankLineBefore(AdocDocument.Kind prevKind) {
        return switch (prevKind) {
            case H1, H2, H3, H4, H5, H6, SectionHeadline -> false;
            default -> true;
        };
    }

    public static AdocDocument parse(String content) {
        AdocDocument doc = new AdocDocument();
        String[] lines = content.split("\n", -1);
        return parse(List.of(lines));
    }


    public static AdocDocument parse(List<String> lines) {
        AdocDocument doc = new AdocDocument();
        int i = 0;

        // Phase 1: Title
        if (i < lines.size() && lines.get(i).startsWith("= ")) {
            doc.title = lines.get(i).substring(2);
            i++;
        }

        // Phase 2: Header attributes (:key: or :key: value)
        while (i < lines.size() && lines.get(i).matches("^:[^:]+:.*$")) {
            doc.headers.add(lines.get(i));
            i++;
        }

        // Phase 3: Content blocks
        String pendingParams = null;
        // Where the current run of [[anchor]] / [attrs] lines began, so the block they belong to can
        // report a span that includes them. -1 when no parameters are pending.
        int pendingParamsLine = -1;
        while (i < lines.size()) {
            String line = lines.get(i);

            // 1. Blank line
            if (line.isBlank()) {
                i++;
                continue;
            }

            // 2. Anchor [[...]]
            if (line.startsWith("[[") && line.endsWith("]]")) {
                if (pendingParams == null) pendingParamsLine = i;
                pendingParams = (pendingParams != null ? pendingParams : "") + line;
                i++;
                continue;
            }

            // 3. Block attributes [...] (not [[)
            if (line.startsWith("[") && !line.startsWith("[[") && line.endsWith("]")) {
                if (pendingParams == null) pendingParamsLine = i;
                pendingParams = (pendingParams != null ? pendingParams : "") + line;
                i++;
                continue;
            }

            // 4. Headline ={2,6} text
            if (line.matches("^={2,6} .+$")) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '=') level++;
                AdocDocument.Kind kind = switch (level) {
                    case 2 -> AdocDocument.Kind.H2;
                    case 3 -> AdocDocument.Kind.H3;
                    case 4 -> AdocDocument.Kind.H4;
                    case 5 -> AdocDocument.Kind.H5;
                    case 6 -> AdocDocument.Kind.H6;
                    default -> AdocDocument.Kind.H2;
                };
                AdocDocument.Block block = new AdocDocument.Block(kind);
                block.lines.add(line.substring(level + 1));
                block.parameters = pendingParams;
                block.startLine = (pendingParams != null ? pendingParamsLine : i) + 1;
                block.endLine = i + 1;
                pendingParams = null;
                pendingParamsLine = -1;
                doc.contents.add(block);
                i++;
                continue;
            }

            // 5. Fenced block markers
            String fenceMarker = null;
            AdocDocument.Kind fenceKind = null;
            switch (line) {
                case "----" -> {
                    fenceMarker = "----";
                    fenceKind = AdocDocument.Kind.Dash4;
                }
                case "...." -> {
                    fenceMarker = "....";
                    fenceKind = AdocDocument.Kind.Dot4;
                }
                case "++++" -> {
                    fenceMarker = "++++";
                    fenceKind = AdocDocument.Kind.Plus4;
                }
                case "****" -> {
                    fenceMarker = "****";
                    fenceKind = AdocDocument.Kind.Star4;
                }
                case "////" -> {
                    fenceMarker = "////";
                    fenceKind = AdocDocument.Kind.Slash4;
                }
                // A bare `====` is the example fence, not a headline: a headline is `==== Text`,
                // which the pattern above requires a space and a title for.
                case "====" -> {
                    fenceMarker = "====";
                    fenceKind = AdocDocument.Kind.Equals4;
                }
                case "____" -> {
                    fenceMarker = "____";
                    fenceKind = AdocDocument.Kind.Underscore4;
                }
                case "--" -> {
                    fenceMarker = "--";
                    fenceKind = AdocDocument.Kind.Dash2;
                }
            }

            if (fenceMarker != null) {
                AdocDocument.Block block = new AdocDocument.Block(fenceKind);
                block.parameters = pendingParams;
                block.startLine = (pendingParams != null ? pendingParamsLine : i) + 1;
                pendingParams = null;
                pendingParamsLine = -1;
                i++;
                while (i < lines.size() && !lines.get(i).equals(fenceMarker)) {
                    block.lines.add(lines.get(i));
                    i++;
                }
                if (i < lines.size()) i++; // skip closing marker
                // i now sits past the closing marker, or past the end of an unterminated block.
                block.endLine = i;
                doc.contents.add(block);
                continue;
            }

            // 6. Section headline .Title (single dot, not .., next char not whitespace)
            if (line.startsWith(".") && !line.startsWith("..") && line.length() > 1 && !Character.isWhitespace(line.charAt(1))) {
                AdocDocument.Block block = new AdocDocument.Block(AdocDocument.Kind.SectionHeadline);
                block.lines.add(line.substring(1));
                block.parameters = pendingParams;
                block.startLine = (pendingParams != null ? pendingParamsLine : i) + 1;
                block.endLine = i + 1;
                pendingParams = null;
                pendingParamsLine = -1;
                doc.contents.add(block);
                i++;
                continue;
            }

            // 7. Line comment // (but not ////)
            if (line.startsWith("//")) {
                AdocDocument.Block block = new AdocDocument.Block(AdocDocument.Kind.LineComment);
                block.parameters = pendingParams;
                block.startLine = (pendingParams != null ? pendingParamsLine : i) + 1;
                pendingParams = null;
                pendingParamsLine = -1;
                while (i < lines.size() && lines.get(i).startsWith("//") && !lines.get(i).equals("////")) {
                    block.lines.add(lines.get(i));
                    i++;
                }
                block.endLine = i;
                doc.contents.add(block);
                continue;
            }

            // 8. Plain text — collect until blank line or block-start pattern
            AdocDocument.Block block = new AdocDocument.Block(AdocDocument.Kind.Plain);
            block.parameters = pendingParams;
            block.startLine = (pendingParams != null ? pendingParamsLine : i) + 1;
            pendingParams = null;
            pendingParamsLine = -1;
            while (i < lines.size() && !lines.get(i).isBlank()) {
                String at = lines.get(i);
                if (isBlockStart(at)) {
                    // A fence a list continuation claimed stays in this run: the `+` above it says
                    // it belongs to the item, and the caller needs to see both to know that.
                    String fence = fenceOf(at);
                    if (fence == null || !claimsWhatFollows(block.lines)) break;
                    block.lines.add(at);
                    i++;
                    while (i < lines.size() && !lines.get(i).equals(fence)) {
                        block.lines.add(lines.get(i));
                        i++;
                    }
                    // The closing marker, or nothing where the block was never closed.
                    if (i < lines.size()) {
                        block.lines.add(lines.get(i));
                        i++;
                    }
                    continue;
                }
                block.lines.add(at);
                i++;
            }
            block.endLine = i;
            doc.contents.add(block);
        }

        return doc;
    }

    public static void write(AdocDocument doc, StringWriter w) {
        // Title
        if (doc.title != null) {
            w.write("= " + doc.title + "\n");
        }
        // Headers
        for (String header : doc.headers) {
            w.write(header + "\n");
        }

        // Content blocks
        AdocDocument.Kind prevKind = null;
        for (AdocDocument.Block block : doc.contents) {
            // Blank line separator
            if (prevKind == null) {
                if (doc.title != null || !doc.headers.isEmpty()) {
                    w.write("\n");
                }
            } else if (needsBlankLineBefore(prevKind)) {
                w.write("\n");
            }

            // Parameters (split concatenated params back into separate lines)
            if (block.parameters != null) {
                Matcher m = PARAMS_PATTERN.matcher(block.parameters);
                while (m.find()) {
                    w.write(m.group() + "\n");
                }
            }

            // Block content
            switch (block.kind) {
                case H1, H2, H3, H4, H5, H6 -> {
                    int level = AdocDocument.Kind.hLevel(block);
                    String prefix = "=".repeat(level) + " ";
                    for (String line : block.lines) {
                        w.write(prefix + line + "\n");
                    }
                }
                case SectionHeadline -> {
                    for (String line : block.lines) {
                        w.write("." + line + "\n");
                    }
                }
                case Dash4 -> writeFenced(block, "----", w);
                case Dot4 -> writeFenced(block, "....", w);
                case Plus4 -> writeFenced(block, "++++", w);
                case Star4 -> writeFenced(block, "****", w);
                case Slash4 -> writeFenced(block, "////", w);
                case Equals4 -> writeFenced(block, "====", w);
                case Underscore4 -> writeFenced(block, "____", w);
                case Dash2 -> writeFenced(block, "--", w);
                default -> {
                    // Plain, LineComment: lines written as-is
                    for (String line : block.lines) {
                        w.write(line + "\n");
                    }
                }
            }

            prevKind = block.kind;
        }
    }

    private static void writeFenced(AdocDocument.Block block, String marker, StringWriter w) {
        w.write(marker + "\n");
        for (String line : block.lines) {
            w.write(line + "\n");
        }
        w.write(marker + "\n");
    }

}
