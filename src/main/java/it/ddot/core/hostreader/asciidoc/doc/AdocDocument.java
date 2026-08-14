package it.ddot.core.hostreader.asciidoc.doc;

import it.ddot.core.hostreader.asciidoc.Adoc2Kg;
import it.ddot.core.hostreader.asciidoc.doc.AdocRenderer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents an AsciiDoc document in a DOM-like fashion.
 */
public class AdocDocument {

    public enum Kind {
        /** Every document starts with such a block, unless defined otherwise using a marker. Blank lines are also here. */
        Plain,
        /**
         * Source code blocks are fenced in `----` lines. The Dash4 block is parsed as code, i.e., all lines are treated
         * verbatim until the Dash4 end marker. The same holds for Plus4 and Slash4.
         */
        Dash4,
        /**
         * Universal blocks. Can contain other blocks?
         */
        Dot4,
        /** Verbatim blocks are fenced in `++++` lines */
        Plus4,
        /** Aside blocks. Can contain other blocks. */
        Star4,
        /** Blocks fenced in `////` lines are commented out. */
        Slash4,

        /** Lines in pattern `.My Headline`. Semantically like a H7 */
        SectionHeadline,

        /**
         * Headline are `= My Headline` with any number of consecutive equal signs, indicating the headline nesting
         * level. E.g. `== Foo` is H2, `=== Foo` is H3, and so on.
         */
        H1, H2, H3, H4, H5, H6,

        /** A span of consecutive lines, each starting with `//` markers denotes comments */
        LineComment,
        ;

        static int hLevel(Block block) {
            return switch (block.kind) {
                case H1 -> 1;
                case H2 -> 2;
                case H3 -> 3;
                case H4 -> 4;
                case H5 -> 5;
                case H6 -> 6;
                default -> 2;
            };
        }
    }

    /**
     * Blocks are constructs either separated by a headline (headline always goes to next block), or by block markers
     * such as `----` (Dash4),`....`,`****`,`____`, `++++`.
     * <p>
     * A block with a headline is represented, e.g., as a Block of kind "H3" with a child block of kind 'Plain'.
     */
    public static class Block {

        public final Kind kind;
        /** The line between a headline and the content, e.g. `[node="concept-123"]` or `[#concept-123]` */
        @Nullable String parameters;
        List<String> lines = new ArrayList<>();

        public Block(Kind kind) {this.kind = kind;}

        public List<String> lines() {
            return lines;
        }

        public @Nullable String parameters() {
            return parameters;
        }

    }

    public static class Section {

        public final String anchorId;

        public @Nullable String nodeType() {
            return nodeType == null || nodeType.isEmpty() ? null : nodeType;
        }

        private final String nodeType;
        public final String headlineText;
        public final int level;
        final List<Block> blocks = new ArrayList<>();

        public Section(String anchorId, String nodeType, String headlineText, int level) {
            this.anchorId = anchorId;
            this.nodeType = nodeType;
            this.headlineText = headlineText;
            this.level = level;
        }

        public List<Block> blocks() {
            return blocks;
        }

    }

    public String title;
    /**
     * E.g. ':toc:' and other header lines
     */
    List<String> headers = new ArrayList<>();
    List<Block> contents = new ArrayList<>();

    /**
     * Groups blocks into sections. A section starts at a headline (H2–H6) and includes all subsequent blocks until the
     * next headline of equal or higher importance (lower or equal level number). Blocks before the first headline form
     * the preamble section.
     */
    public static List<Section> groupSections(List<Block> blocks) {
        List<Section> sections = new ArrayList<>();
        Section current = new Section(null, null, null, 0); // preamble
        sections.add(current);

        for (Block block : blocks) {
            int level = headlineLevel(block.kind);
            if (level > 0) {
                // This is a headline — start a new section if same or higher importance than current
                if (current.level == 0 || level <= current.level) {
                    // New top-level section
                    String anchorId = AdocRenderer.extractId(block.parameters());
                    String nodeType = Adoc2Kg.extractKgType(block.parameters());
                    String headlineText = String.join(" ", block.lines());
                    current = new Section(anchorId, nodeType, headlineText, level);
                    sections.add(current);
                } else {
                    // Sub-section: include headline as content of parent section
                    current.blocks.add(block);
                }
            } else {
                current.blocks.add(block);
            }
        }

        return sections;
    }

    public static int headlineLevel(Kind kind) {
        return switch (kind) {
            case H1 -> 1;
            case H2 -> 2;
            case H3 -> 3;
            case H4 -> 4;
            case H5 -> 5;
            case H6 -> 6;
            default -> 0;
        };
    }

    public List<Block> contents() {
        return contents;
    }

    public List<String> headers() {
        return headers;
    }

    public String title() {
        return title;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AdocDocument: ").append(title).append("\n");
        if (!headers.isEmpty()) {
            sb.append("  Headers:\n");
            for (String header : headers) {
                sb.append("    ").append(header).append("\n");
            }
        }
        sb.append("  Contents:\n");
        for (Block block : contents) {
            renderBlock(sb, block, 2);
        }
        return sb.toString();
    }

    private void renderBlock(StringBuilder sb, Block block, int indent) {
        String indentation = "  ".repeat(indent);
        sb.append(indentation).append("Block[").append(block.kind).append("]");
        if (block.parameters != null) {
            sb.append(" params=").append(block.parameters);
        }
        sb.append("\n");
        for (String line : block.lines) {
            sb.append(indentation).append("  | ").append(line).append("\n");
        }
    }

}
