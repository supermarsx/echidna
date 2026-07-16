package com.echidna.magisk;

/** Dependency-free fixtures for API 26-33 legacy effect configuration shapes. */
public final class EffectConfigMergerFixtureTest {
    private static final String IMPL = EffectConfigMerger.IMPLEMENTATION_UUID;

    private EffectConfigMergerFixtureTest() {}

    public static void main(String[] arguments) throws Exception {
        api26LegacyConfPreservesVendorEntries();
        api28XmlRegistersWithoutAutoApply();
        api30NamespacedXmlPreservesProcessing();
        api33XmlIsIdempotent();
        allSupportedSdkFixturesAcceptXmlAndConf();
        rejectsMalformedAmbiguousDuplicateAndConflictingInputs();
        rejectsMaliciousAndUnknownXmlSchemas();
        rejectsUnsupportedXmlNodesAndPreservesComments();
        System.out.println(
                "effect config fixtures: API26/API28/API30/API33/security/idempotence/conflict PASS");
    }

    private static void api26LegacyConfPreservesVendorEntries() throws Exception {
        String source = "# API 26 vendor fixture\n"
                + "libraries {\n"
                + "  vendor_bundle {\n"
                + "    path /vendor/lib/soundfx/libvendor.so\n"
                + "  }\n"
                + "}\n"
                + "effects {\n"
                + "  vendor_fx {\n"
                + "    library vendor_bundle\n"
                + "    uuid 11111111-2222-3333-4444-555555555555\n"
                + "  }\n"
                + "}\n"
                + "pre_processing {\n"
                + "  mic {\n"
                + "    vendor_fx {\n"
                + "    }\n"
                + "  }\n"
                + "}\n";
        EffectConfigMerger.Result merged = EffectConfigMerger.merge(
                source,
                EffectConfigMerger.Format.LEGACY_CONF,
                "/vendor/lib/soundfx/libechidna_preproc.so");
        require(merged.changed);
        require(merged.contents.contains("# API 26 vendor fixture"));
        require(merged.contents.contains("path /vendor/lib/soundfx/libvendor.so"));
        require(merged.contents.contains("uuid " + IMPL));
        require(!merged.contents.substring(merged.contents.indexOf("pre_processing"))
                .contains(EffectConfigMerger.EFFECT_NAME));
        EffectConfigMerger.Result repeated = EffectConfigMerger.merge(
                merged.contents,
                EffectConfigMerger.Format.LEGACY_CONF,
                "/vendor/lib/soundfx/libechidna_preproc.so");
        require(!repeated.changed && repeated.contents.equals(merged.contents));
    }

    private static void api28XmlRegistersWithoutAutoApply() throws Exception {
        String source = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<audio_effects_conf version=\"2.0\">\n"
                + "  <libraries><library name=\"vendor\" path=\"libvendor.so\"/></libraries>\n"
                + "  <effects><effect name=\"vendor_fx\" library=\"vendor\" "
                + "uuid=\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"/></effects>\n"
                + "</audio_effects_conf>\n";
        EffectConfigMerger.Result merged = EffectConfigMerger.merge(
                source, EffectConfigMerger.Format.XML, null);
        require(merged.changed);
        require(merged.contents.contains("path=\"libvendor.so\""));
        require(merged.contents.contains("uuid=\"" + IMPL + "\""));
        require(!merged.contents.contains("<preprocess"));
    }

    private static void api30NamespacedXmlPreservesProcessing() throws Exception {
        String source = "<audio_effects_conf version=\"2.0\" "
                + "xmlns=\"http://schemas.android.com/audio/audio_effects_conf/v2_0\">\n"
                + "<libraries><library name=\"vendor\" path=\"libvendor.so\"/></libraries>\n"
                + "<effects><effect name=\"vendor_fx\" library=\"vendor\" "
                + "uuid=\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"/></effects>\n"
                + "<preprocess><stream type=\"mic\"><apply effect=\"vendor_fx\"/>"
                + "</stream></preprocess>\n"
                + "</audio_effects_conf>\n";
        EffectConfigMerger.Result merged = EffectConfigMerger.merge(
                source, EffectConfigMerger.Format.XML, null);
        require(merged.contents.contains("<apply effect=\"vendor_fx\"/>"));
        int preprocess = merged.contents.indexOf("<preprocess>");
        int end = merged.contents.indexOf("</preprocess>");
        require(preprocess > 0 && end > preprocess);
        require(!merged.contents.substring(preprocess, end)
                .contains(EffectConfigMerger.EFFECT_NAME));
    }

    private static void api33XmlIsIdempotent() throws Exception {
        String source = "<audio_effects_conf version=\"2.0\">\n"
                + "<libraries>\n"
                + "<library name=\"echidna_preproc\" path=\"libechidna_preproc.so\"/>\n"
                + "</libraries>\n"
                + "<effects>\n"
                + "<effect name=\"echidna_preprocessor\" library=\"echidna_preproc\" "
                + "uuid=\"" + IMPL + "\"/>\n"
                + "</effects>\n"
                + "</audio_effects_conf>\n";
        EffectConfigMerger.Result merged = EffectConfigMerger.merge(
                source, EffectConfigMerger.Format.XML, null);
        require(!merged.changed && merged.contents.equals(source));
    }

    private static void allSupportedSdkFixturesAcceptXmlAndConf() throws Exception {
        for (int sdk : new int[] {26, 28, 30, 33}) {
            String xml = "<audio_effects_conf version=\"2.0\"><libraries/>"
                    + "<effects/></audio_effects_conf>";
            EffectConfigMerger.Result xmlResult = EffectConfigMerger.merge(
                    xml, EffectConfigMerger.Format.XML, null);
            require(xmlResult.changed && xmlResult.contents.contains("uuid=\"" + IMPL + "\""));

            String conf = "# API " + sdk + " fixture\nlibraries {\n}\neffects {\n}\n";
            EffectConfigMerger.Result confResult = EffectConfigMerger.merge(
                    conf,
                    EffectConfigMerger.Format.LEGACY_CONF,
                    "/vendor/lib/soundfx/libechidna_preproc.so");
            require(confResult.changed && confResult.contents.contains("uuid " + IMPL));
        }
    }

    private static void rejectsMalformedAmbiguousDuplicateAndConflictingInputs() {
        rejects(() -> EffectConfigMerger.merge(
                "<audio_effects_conf version=\"2.0\"><libraries></audio_effects_conf>",
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "<audio_effects_conf version=\"2.0\"><libraries/><libraries/><effects/>"
                        + "</audio_effects_conf>",
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "<audio_effects_conf version=\"2.0\"><libraries>"
                        + "<library name=\"echidna_preproc\" "
                        + "path=\"wrong.so\"/></libraries><effects/></audio_effects_conf>",
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "<audio_effects_conf version=\"2.0\"><libraries/><effects/>"
                        + "<preprocess><stream><apply effect=\"echidna_preprocessor\"/>"
                        + "</stream></preprocess></audio_effects_conf>",
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "libraries {\n}\nlibraries {\n}\neffects {\n}\n",
                EffectConfigMerger.Format.LEGACY_CONF,
                "/system/lib/soundfx/libechidna_preproc.so"));
        rejects(() -> EffectConfigMerger.merge(
                "libraries {\n  echidna_preproc {\n    path /system/lib/soundfx/wrong.so\n"
                        + "  }\n}\neffects {\n}\n",
                EffectConfigMerger.Format.LEGACY_CONF,
                "/system/lib/soundfx/libechidna_preproc.so"));
        rejects(() -> EffectConfigMerger.merge(
                "libraries {\n}\neffects {\n}\npre_processing {\n"
                        + "  apply echidna_preprocessor\n}\n",
                EffectConfigMerger.Format.LEGACY_CONF,
                "/system/lib/soundfx/libechidna_preproc.so"));
    }

    private static void rejectsMaliciousAndUnknownXmlSchemas() {
        String validBody = "<audio_effects_conf version=\"2.0\"><libraries/><effects/>"
                + "</audio_effects_conf>";
        rejects(() -> EffectConfigMerger.merge(
                "<!DOCTYPE audio_effects_conf [<!ENTITY e SYSTEM \"file:///data/secret\">]>"
                        + validBody,
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "<!DOCTYPE audio_effects_conf [<!ENTITY e \"expanded\">]>"
                        + "<audio_effects_conf version=\"2.0\"><libraries>&e;</libraries>"
                        + "<effects/></audio_effects_conf>",
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "<!DOCTYPE audio_effects_conf ["
                        + "<!ENTITY % external SYSTEM \"file:///data/secret\">%external;]>"
                        + validBody,
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "<audio_effects_conf version=\"2.0\" "
                        + "xmlns:xi=\"http://www.w3.org/2001/XInclude\">"
                        + "<libraries><xi:include href=\"file:///data/secret\"/></libraries>"
                        + "<effects/></audio_effects_conf>",
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "<audio_effects_conf version=\"2.0\" "
                        + "xmlns:xi=\"http://www.w3.org/2001/XInclude\">"
                        + "<libraries><xi:fallback/></libraries><effects/>"
                        + "</audio_effects_conf>",
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "<audio_effects_conf version=\"9.9\"><libraries/><effects/>"
                        + "</audio_effects_conf>",
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "<audio_effects_conf version=\"2.0\" xmlns=\"urn:unknown-audio-schema\">"
                        + "<libraries/><effects/></audio_effects_conf>",
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "<unknown_effects_conf version=\"2.0\"><libraries/><effects/>"
                        + "</unknown_effects_conf>",
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "<?xml version=\"1.0\" encoding=\"UTF-16\"?>" + validBody,
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "\ufeff<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + validBody,
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "\ufffe" + validBody,
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "<!--before--><?xml version=\"1.0\"?>" + validBody,
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "<! DOCTYPE audio_effects_conf>" + validBody,
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                oversizedXml(),
                EffectConfigMerger.Format.XML,
                null));
    }

    private static String oversizedXml() {
        StringBuilder oversized = new StringBuilder(4 * 1024 * 1024 + 1);
        while (oversized.length() <= 4 * 1024 * 1024) {
            oversized.append(' ');
        }
        return oversized.toString();
    }

    private static void rejectsUnsupportedXmlNodesAndPreservesComments() throws Exception {
        rejects(() -> EffectConfigMerger.merge(
                "<?unsupported before?><audio_effects_conf version=\"2.0\">"
                        + "<libraries/><effects/>"
                        + "</audio_effects_conf>",
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "<audio_effects_conf version=\"2.0\"><libraries>"
                        + "<?unsupported inside?></libraries><effects/>"
                        + "</audio_effects_conf>",
                EffectConfigMerger.Format.XML,
                null));
        rejects(() -> EffectConfigMerger.merge(
                "<audio_effects_conf version=\"2.0\"><libraries>"
                        + "<![CDATA[discarded]]></libraries><effects/>"
                        + "</audio_effects_conf>",
                EffectConfigMerger.Format.XML,
                null));
        String withComments = "<!--before--><audio_effects_conf version=\"2.0\">"
                + "<libraries/><effects/></audio_effects_conf><!--after-->";
        EffectConfigMerger.Result merged = EffectConfigMerger.merge(
                withComments, EffectConfigMerger.Format.XML, null);
        require(merged.contents.contains("<!--before-->"));
        require(merged.contents.contains("<!--after-->"));
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError("fixture requirement failed");
        }
    }

    private static void rejects(CheckedRunnable operation) {
        try {
            operation.run();
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError("expected fixture rejection");
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
