package com.mbreissi.edgecommons.opcua.opc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bounded evaluation of caller-supplied matcher regexes.
 *
 * <p>{@code sb/read} accepts {@code include}/{@code exclude} patterns from the bus and evaluates them
 * against <b>every</b> node in the cached address space. Even a moderately expensive pattern is
 * therefore multiplied by the node count, and the caller controls the pattern.
 *
 * <p>Two guards, both cheap and both false-positive-free for the short identifiers and browse names
 * this adapter matches against:
 * <ul>
 *   <li><b>Length</b> — a pattern longer than the configured cap is refused at parse time.</li>
 *   <li><b>Step budget</b> — the input is presented to the matcher through a {@link CharSequence} that
 *       counts character accesses and aborts once the budget is spent.</li>
 * </ul>
 *
 * <p>A note on what the budget does and does not claim. Java's {@link Pattern} is markedly more
 * resistant to the classic "evil regex" shapes than a naive backtracker — {@code (a+)+$} against a
 * non-matching input runs in linear time here, not exponential. The budget is therefore a <b>work
 * bound</b>, not an exponential-blowup detector: character accesses grow super-linearly with nested
 * quantifiers (a pattern like {@code (x+x+)+y} costs roughly quadratically in the input length), and
 * the budget caps that growth per match so no single request can monopolise the command thread.
 */
public final class SafeRegex {

    private SafeRegex() {
    }

    /** Raised when a match exceeds its step budget — mapped to {@code BAD_ARGS} by the command layer. */
    public static final class BudgetExceededException extends RuntimeException {
        BudgetExceededException(String message) {
            super(message);
        }
    }

    /**
     * Compile a caller-supplied pattern, refusing anything over {@code maxLength}.
     *
     * @throws IllegalArgumentException when the pattern is too long or syntactically invalid
     */
    public static Pattern compile(String regex, int maxLength) {
        if (regex == null) {
            throw new IllegalArgumentException("matcher regex is required");
        }
        if (maxLength > 0 && regex.length() > maxLength) {
            throw new IllegalArgumentException("matcher regex is " + regex.length()
                    + " characters; the limit is " + maxLength);
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid matcher regex '" + regex + "': " + e.getDescription());
        }
    }

    /**
     * Evaluate {@code pattern.matcher(input).matches()} under a character-access budget.
     *
     * @throws BudgetExceededException when the match exhausts {@code stepBudget} character accesses
     */
    public static boolean matches(Pattern pattern, String input, int stepBudget) {
        if (pattern == null || input == null) {
            return false;
        }
        if (stepBudget <= 0) {
            return pattern.matcher(input).matches();
        }
        Matcher matcher = pattern.matcher(new BudgetedCharSequence(input, stepBudget));
        return matcher.matches();
    }

    /**
     * A read-only view over a string that aborts once the matcher has read more characters than the
     * budget allows. {@code length()} is free; only {@code charAt} is metered, which is what a
     * backtracking engine burns.
     */
    private static final class BudgetedCharSequence implements CharSequence {

        private final CharSequence delegate;
        private final int budget;
        private final int[] spent;

        BudgetedCharSequence(CharSequence delegate, int budget) {
            this(delegate, budget, new int[1]);
        }

        private BudgetedCharSequence(CharSequence delegate, int budget, int[] spent) {
            this.delegate = delegate;
            this.budget = budget;
            this.spent = spent;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (++spent[0] > budget) {
                throw new BudgetExceededException("matcher regex exceeded its " + budget
                        + "-step evaluation budget; simplify the pattern");
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            // Share the same counter so a sub-sequence cannot reset the budget.
            return new BudgetedCharSequence(delegate.subSequence(start, end), budget, spent);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
