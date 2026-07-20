package com.echidna.lsposed.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StrictJsonValidatorTest {

    private static final int MAX_DEPTH = 128;

    @Test
    public void acceptsCanonicalDocumentsAndBareTopLevelValues() {
        assertTrue(StrictJsonValidator.isSafe("{}"));
        assertTrue(StrictJsonValidator.isSafe("[]"));
        assertTrue(StrictJsonValidator.isSafe(
                "{\"schemaVersion\":2,\"control\":{\"masterEnabled\":true,\"bypass\":false},"
                        + "\"modules\":[1,-2.5,1e3,null],\"id\":\"voice-preset\"}"));
        assertTrue(StrictJsonValidator.isSafe("true"));
        assertTrue(StrictJsonValidator.isSafe("false"));
        assertTrue(StrictJsonValidator.isSafe("null"));
        assertTrue(StrictJsonValidator.isSafe("0"));
        assertTrue(StrictJsonValidator.isSafe("\"\""));
        assertTrue(StrictJsonValidator.isSafe(" \t\r\n{ \"a\" : [ 1 , 2 ] } \t\r\n"));
    }

    @Test
    public void nullAndEmptyAndWhitespaceOnlyDocumentsAreRejected() {
        assertFalse(StrictJsonValidator.isSafe(null));
        assertFalse(StrictJsonValidator.isSafe(""));
        assertFalse(StrictJsonValidator.isSafe("   "));
        assertFalse(StrictJsonValidator.isSafe("\n\t\r "));
    }

    @Test
    public void duplicateKeysAreRejectedEvenWhenSpelledDifferently() {
        assertFalse(StrictJsonValidator.isSafe("{\"a\":1,\"a\":2}"));
        // org.json keeps the last binding for a duplicate key, so an escape-aliased spelling is a
        // policy-override primitive unless the pre-scan decodes before comparing.
        assertFalse(StrictJsonValidator.isSafe("{\"a\":1,\"\\u0061\":2}"));
        assertFalse(StrictJsonValidator.isSafe("{\"a\\/b\":1,\"a/b\":2}"));
        assertFalse(StrictJsonValidator.isSafe("{\"masterEnabled\":false,\"masterEnabled\":true}"));
        assertFalse(StrictJsonValidator.isSafe("{\"o\":{\"a\":1,\"a\":2}}"));
        assertFalse(StrictJsonValidator.isSafe("[{\"a\":1,\"a\":2}]"));

        // Uniqueness is scoped per object: sibling and nested objects may reuse a key.
        assertTrue(StrictJsonValidator.isSafe("{\"a\":{\"a\":1},\"b\":{\"a\":2}}"));
        assertTrue(StrictJsonValidator.isSafe("[{\"a\":1},{\"a\":2}]"));
    }

    @Test
    public void trailingContentAndStructuralDamageAreRejected() {
        assertFalse(StrictJsonValidator.isSafe("{}{}"));
        assertFalse(StrictJsonValidator.isSafe("{} garbage"));
        assertFalse(StrictJsonValidator.isSafe("[1,2]null"));
        assertTrue(StrictJsonValidator.isSafe("[1,2]  "));
        assertFalse(StrictJsonValidator.isSafe("{"));
        assertFalse(StrictJsonValidator.isSafe("}"));
        assertFalse(StrictJsonValidator.isSafe("["));
        assertFalse(StrictJsonValidator.isSafe("]"));
        assertFalse(StrictJsonValidator.isSafe("{\"a\":1,}"));
        assertFalse(StrictJsonValidator.isSafe("[1,]"));
        assertFalse(StrictJsonValidator.isSafe("[,1]"));
        assertFalse(StrictJsonValidator.isSafe("{,}"));
        assertFalse(StrictJsonValidator.isSafe("{\"a\"}"));
        assertFalse(StrictJsonValidator.isSafe("{\"a\":}"));
        assertFalse(StrictJsonValidator.isSafe("{a:1}"));
        assertFalse(StrictJsonValidator.isSafe("{'a':1}"));
        assertFalse(StrictJsonValidator.isSafe("[1 2]"));
        assertFalse(StrictJsonValidator.isSafe("{\"a\":1 \"b\":2}"));
        assertFalse(StrictJsonValidator.isSafe("[1,2"));
    }

    @Test
    public void nonJsonNumberSpellingsAreRejectedBeforeOrgJsonCanNormalizeThem() {
        assertTrue(StrictJsonValidator.isSafe("[-0,0,1,-1.5,1e3,1E+2,-2.5e-10,1234567890]"));

        for (String primitive : new String[] {
                "01", "-01", "+1", ".5", "1.", "-", "-.5", "1e", "1e+", "1E-", "1..2", "1.2.3",
                "0x10", "1f", "010", "Infinity", "-Infinity", "NaN", "TRUE", "True", "nul",
                "nulls", "truex", "--1"}) {
            assertFalse("expected rejection of primitive: " + primitive,
                    StrictJsonValidator.isSafe(primitive));
            assertFalse("expected rejection inside array: " + primitive,
                    StrictJsonValidator.isSafe("[" + primitive + "]"));
            assertFalse("expected rejection as object value: " + primitive,
                    StrictJsonValidator.isSafe("{\"a\":" + primitive + "}"));
        }
    }

    @Test
    public void invalidEscapesAndRawControlCharactersInStringsAreRejected() {
        assertTrue(StrictJsonValidator.isSafe("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0041\""));

        assertFalse(StrictJsonValidator.isSafe("\"\\a\""));
        assertFalse(StrictJsonValidator.isSafe("\"\\x41\""));
        assertFalse(StrictJsonValidator.isSafe("\"\\\""));
        assertFalse(StrictJsonValidator.isSafe("\"\\u12\""));
        assertFalse(StrictJsonValidator.isSafe("\"\\u00ZZ\""));
        assertFalse(StrictJsonValidator.isSafe("\"\\u 041\""));
        assertFalse(StrictJsonValidator.isSafe("\"unterminated"));

        // Raw C0 control characters must be escaped; a raw newline or tab inside a string is not
        // legal JSON even though several lenient parsers accept it.
        for (char control = 0; control < 0x20; control++) {
            assertFalse("expected rejection of raw control char " + (int) control,
                    StrictJsonValidator.isSafe(stringContaining(control)));
        }
        assertTrue(StrictJsonValidator.isSafe(stringContaining(' ')));
        assertTrue(StrictJsonValidator.isSafe(stringContaining((char) 0x7f)));
    }

    @Test
    public void formFeedAndVerticalTabAreNotJsonWhitespace() {
        assertFalse(StrictJsonValidator.isSafe("\f{}"));
        assertFalse(StrictJsonValidator.isSafe("{}\f"));
        assertFalse(StrictJsonValidator.isSafe(String.valueOf((char) 0x0b) + "{}"));
        assertFalse(StrictJsonValidator.isSafe("[1\f,2]"));
    }

    @Test
    public void loneSurrogatesAreRejectedRawAndWhenSpelledAsEscapes() {
        assertFalse(StrictJsonValidator.isWellFormedUtf16(null));
        assertTrue(StrictJsonValidator.isWellFormedUtf16(""));
        assertTrue(StrictJsonValidator.isWellFormedUtf16("😀"));
        assertFalse(StrictJsonValidator.isWellFormedUtf16("\ud83d"));
        assertFalse(StrictJsonValidator.isWellFormedUtf16("\ude00"));
        assertFalse(StrictJsonValidator.isWellFormedUtf16("a\ud83dz"));
        assertFalse(StrictJsonValidator.isWellFormedUtf16("\ud83d😀"));
        assertTrue(StrictJsonValidator.isWellFormedUtf16("😀😀"));

        // Raw in the document text.
        assertFalse(StrictJsonValidator.isSafe("{\"\ud83d\":1}"));
        assertFalse(StrictJsonValidator.isSafe("[\"\ude00\"]"));
        assertTrue(StrictJsonValidator.isSafe("{\"😀\":1}"));

        // Assembled from hex escapes, which survive a naive UTF-16 scan of the raw document text.
        assertFalse(StrictJsonValidator.isSafe("\"\\ud83d\""));
        assertFalse(StrictJsonValidator.isSafe("\"\\ude00\""));
        assertFalse(StrictJsonValidator.isSafe("\"\\ud83da\""));
        assertFalse(StrictJsonValidator.isSafe("{\"\\udc00\":1}"));
        assertTrue(StrictJsonValidator.isSafe("\"\\ud83d\\ude00\""));
        assertTrue(StrictJsonValidator.isSafe("\"\\u0000\""));
    }

    @Test
    public void nestingIsAcceptedUpToTheCeilingAndRejectedOneLevelBeyond() {
        // An empty innermost container costs no further budget, so the last openable bracket sits
        // one level past the ceiling.
        assertTrue(StrictJsonValidator.isSafe(nestedArrays(MAX_DEPTH + 1)));
        assertFalse(StrictJsonValidator.isSafe(nestedArrays(MAX_DEPTH + 2)));
        assertFalse(StrictJsonValidator.isSafe(nestedArrays(4096)));

        // An object always has to hold a value, and that value is read at the object's own depth,
        // so a nest that actually carries a payload tops out one level earlier than an empty one.
        assertTrue(StrictJsonValidator.isSafe(nestedObjects(MAX_DEPTH)));
        assertFalse(StrictJsonValidator.isSafe(nestedObjects(MAX_DEPTH + 1)));
        assertFalse(StrictJsonValidator.isSafe(nestedObjects(4096)));

        // The ceiling counts depth, not breadth: a wide-but-shallow document stays acceptable.
        StringBuilder wide = new StringBuilder("[");
        for (int i = 0; i < 4096; i++) {
            wide.append(i == 0 ? "" : ",").append("[1]");
        }
        assertTrue(StrictJsonValidator.isSafe(wide.append("]").toString()));
    }

    @Test
    public void largeWellFormedPayloadsStayAcceptedBecauseSizeIsCappedByTheCaller() {
        StringBuilder builder = new StringBuilder("{\"k\":\"");
        for (int i = 0; i < 200_000; i++) {
            builder.append('a');
        }
        assertTrue(StrictJsonValidator.isSafe(builder.append("\"}").toString()));
    }

    private static String stringContaining(char raw) {
        return "\"a" + raw + "b\"";
    }

    private static String nestedArrays(int depth) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            builder.append('[');
        }
        for (int i = 0; i < depth; i++) {
            builder.append(']');
        }
        return builder.toString();
    }

    private static String nestedObjects(int depth) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            builder.append("{\"a\":");
        }
        builder.append('1');
        for (int i = 0; i < depth; i++) {
            builder.append('}');
        }
        return builder.toString();
    }
}
