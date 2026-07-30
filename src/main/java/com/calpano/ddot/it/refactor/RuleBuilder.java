package com.calpano.ddot.it.refactor;

import java.util.ArrayList;
import java.util.List;

public class RuleBuilder {

    public static RewriteRuleBuilder rule() {
        return new RewriteRuleBuilder();
    }

    public static TriplePatternBuilder pattern() {
        return new TriplePatternBuilder();
    }

    public static TripleTemplateBuilder template() {
        return new TripleTemplateBuilder();
    }

    public static class RewriteRuleBuilder {
        private final List<TriplePattern> match = new ArrayList<>();
        private RewriteRule.RemovalMode removalMode = RewriteRule.RemovalMode.KEEP;
        private RewriteRule.Placement placement = RewriteRule.Placement.NEXT_TO_PRIMARY;
        private final List<TripleTemplate> add = new ArrayList<>();
        private final List<RewriteRule.Filter> filters = new ArrayList<>();

        public RewriteRuleBuilder match(TriplePattern pattern) {
            this.match.add(pattern);
            return this;
        }

        public RewriteRuleBuilder match(TriplePatternBuilder patternBuilder) {
            this.match.add(patternBuilder.build());
            return this;
        }

        public RewriteRuleBuilder removeMatched() {
            this.removalMode = RewriteRule.RemovalMode.DELETE;
            return this;
        }

        public RewriteRuleBuilder deprecateMatched() {
            this.removalMode = RewriteRule.RemovalMode.DEPRECATE;
            return this;
        }

        public RewriteRuleBuilder add(TripleTemplate template) {
            this.add.add(template);
            return this;
        }

        public RewriteRuleBuilder add(TripleTemplateBuilder templateBuilder) {
            this.add.add(templateBuilder.build());
            return this;
        }

        public RewriteRule build() {
            return new RewriteRule(match, removalMode, placement, add, filters);
        }
    }

    public static class TriplePatternBuilder {
        private MatchTerm s = MatchTerm.any();
        private MatchTerm p = MatchTerm.any();
        private MatchTerm o = MatchTerm.any();

        public TriplePatternBuilder s(MatchTerm term) { this.s = term; return this; }
        public TriplePatternBuilder sVar(String name) { this.s = MatchTerm.var(name); return this; }
        public TriplePatternBuilder sVar(String name, StringMatcher matcher) { this.s = MatchTerm.var(name, matcher); return this; }
        public TriplePatternBuilder sLit(String val) { this.s = MatchTerm.literal(val); return this; }
        public TriplePatternBuilder sAny() { this.s = MatchTerm.any(); return this; }

        public TriplePatternBuilder p(MatchTerm term) { this.p = term; return this; }
        public TriplePatternBuilder pVar(String name) { this.p = MatchTerm.var(name); return this; }
        public TriplePatternBuilder pVar(String name, StringMatcher matcher) { this.p = MatchTerm.var(name, matcher); return this; }
        public TriplePatternBuilder pLit(String val) { this.p = MatchTerm.literal(val); return this; }
        public TriplePatternBuilder pAny() { this.p = MatchTerm.any(); return this; }

        public TriplePatternBuilder o(MatchTerm term) { this.o = term; return this; }
        public TriplePatternBuilder oVar(String name) { this.o = MatchTerm.var(name); return this; }
        public TriplePatternBuilder oVar(String name, StringMatcher matcher) { this.o = MatchTerm.var(name, matcher); return this; }
        public TriplePatternBuilder oLit(String val) { this.o = MatchTerm.literal(val); return this; }
        public TriplePatternBuilder oAny() { this.o = MatchTerm.any(); return this; }

        public TriplePattern build() {
            return new TriplePattern(s, p, o);
        }
    }

    public static class TripleTemplateBuilder {
        private OutTerm s = OutTerm.constant("");
        private OutTerm p = OutTerm.constant("");
        private OutTerm o = OutTerm.constant("");

        public TripleTemplateBuilder s(OutTerm term) { this.s = term; return this; }
        public TripleTemplateBuilder sVar(String name) { this.s = OutTerm.varRef(name); return this; }
        public TripleTemplateBuilder sConst(String val) { this.s = OutTerm.constant(val); return this; }
        public TripleTemplateBuilder sTemplate(String val) { this.s = OutTerm.template(val); return this; }

        public TripleTemplateBuilder p(OutTerm term) { this.p = term; return this; }
        public TripleTemplateBuilder pVar(String name) { this.p = OutTerm.varRef(name); return this; }
        public TripleTemplateBuilder pConst(String val) { this.p = OutTerm.constant(val); return this; }
        public TripleTemplateBuilder pTemplate(String val) { this.p = OutTerm.template(val); return this; }

        public TripleTemplateBuilder o(OutTerm term) { this.o = term; return this; }
        public TripleTemplateBuilder oVar(String name) { this.o = OutTerm.varRef(name); return this; }
        public TripleTemplateBuilder oConst(String val) { this.o = OutTerm.constant(val); return this; }
        public TripleTemplateBuilder oTemplate(String val) { this.o = OutTerm.template(val); return this; }

        public TripleTemplate build() {
            return new TripleTemplate(s, p, o);
        }
    }
}
