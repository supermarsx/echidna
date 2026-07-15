package com.echidna.magisk;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Deterministic, fail-closed merger for legacy Android audio-effect registries. */
public final class EffectConfigMerger {
    public static final String LIBRARY_NAME = "echidna_preproc";
    public static final String LIBRARY_FILE = "libechidna_preproc.so";
    public static final String EFFECT_NAME = "echidna_preprocessor";
    public static final String TYPE_UUID = "c83e3db3-d4f5-5f2c-a095-8775c1edfc6d";
    public static final String IMPLEMENTATION_UUID = "3e66a36e-dee9-5d81-a0d6-49fc3b863530";

    private EffectConfigMerger() {}

    public static final class Format {
        public static final int XML = 1;
        public static final int LEGACY_CONF = 2;

        private Format() {}

        public static int parse(String value) {
            if ("xml".equals(value)) {
                return XML;
            }
            if ("conf".equals(value)) {
                return LEGACY_CONF;
            }
            throw new IllegalArgumentException("effect config format must be xml or conf");
        }
    }

    public static final class Result {
        public final String contents;
        public final boolean changed;

        Result(String contents, boolean changed) {
            this.contents = contents;
            this.changed = changed;
        }
    }

    public static Result merge(String source, int format, String legacyLibraryPath)
            throws Exception {
        if (source == null || source.isEmpty() || source.indexOf('\u0000') >= 0
                || source.indexOf('\ufffd') >= 0) {
            throw new IllegalArgumentException("effect config is empty or is not strict UTF-8");
        }
        if (format == Format.XML) {
            return mergeXml(source);
        }
        if (format != Format.LEGACY_CONF) {
            throw new IllegalArgumentException("unknown effect config format constant");
        }
        if (legacyLibraryPath == null
                || !legacyLibraryPath.matches("/(system|vendor)/(lib|lib64)/soundfx/"
                        + "libechidna_preproc\\.so")) {
            throw new IllegalArgumentException("legacy library path is not a supported soundfx path");
        }
        return mergeLegacy(source, legacyLibraryPath);
    }

    private static Result mergeXml(String source) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setRequiredFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setRequiredFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setRequiredFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        InputSource input = new InputSource(
                new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
        input.setEncoding("UTF-8");
        Document document = factory.newDocumentBuilder().parse(input);
        validateSupportedNodes(document);
        Element root = document.getDocumentElement();
        if (root == null || !"audio_effects_conf".equals(localName(root))) {
            throw new IllegalArgumentException("XML root must be audio_effects_conf");
        }
        Element libraries = requireSingleDirectChild(root, "libraries");
        Element effects = requireSingleDirectChild(root, "effects");

        Map<String, Element> libraryNames = uniqueNamedChildren(libraries, "library");
        Map<String, Element> effectNames = uniqueEffectChildren(effects);
        rejectUnexpectedEchidnaReferences(root, effects);

        boolean changed = false;
        Element library = libraryNames.get(LIBRARY_NAME);
        if (library == null) {
            rejectAttributeCollision(libraries, "library", "path", LIBRARY_FILE, LIBRARY_NAME);
            library = createElementLike(document, root, "library");
            library.setAttribute("name", LIBRARY_NAME);
            library.setAttribute("path", LIBRARY_FILE);
            libraries.appendChild(library);
            changed = true;
        } else if (!LIBRARY_FILE.equals(library.getAttribute("path"))
                || library.getAttributes().getLength() != 2) {
            throw new IllegalArgumentException("conflicting Echidna library registration");
        }

        Element effect = effectNames.get(EFFECT_NAME);
        if (effect == null) {
            rejectAttributeCollision(effects, "effect", "uuid", IMPLEMENTATION_UUID, EFFECT_NAME);
            effect = createElementLike(document, root, "effect");
            effect.setAttribute("name", EFFECT_NAME);
            effect.setAttribute("library", LIBRARY_NAME);
            effect.setAttribute("uuid", IMPLEMENTATION_UUID);
            effects.appendChild(effect);
            changed = true;
        } else if (!"effect".equals(localName(effect))
                || !LIBRARY_NAME.equals(effect.getAttribute("library"))
                || !IMPLEMENTATION_UUID.equalsIgnoreCase(effect.getAttribute("uuid"))
                || effect.getAttributes().getLength() != 3) {
            throw new IllegalArgumentException("conflicting Echidna effect registration");
        }

        String canonical = serialize(document);
        return new Result(changed ? canonical : source, changed);
    }

    private static void setRequiredFeature(
            DocumentBuilderFactory factory, String feature, boolean value) throws Exception {
        factory.setFeature(feature, value);
        if (factory.getFeature(feature) != value) {
            throw new IllegalStateException("XML parser did not accept secure feature " + feature);
        }
    }

    private static Element requireSingleDirectChild(Element parent, String expected) {
        Element result = null;
        for (Element child : directChildren(parent)) {
            if (expected.equals(localName(child))) {
                if (result != null) {
                    throw new IllegalArgumentException("duplicate XML section: " + expected);
                }
                result = child;
            }
        }
        if (result == null) {
            throw new IllegalArgumentException("missing XML section: " + expected);
        }
        return result;
    }

    private static Map<String, Element> uniqueNamedChildren(Element parent, String childName) {
        Map<String, Element> result = new HashMap<>();
        for (Element child : directChildren(parent)) {
            if (!childName.equals(localName(child))) {
                continue;
            }
            String name = requiredAttribute(child, "name");
            if (result.put(name, child) != null) {
                throw new IllegalArgumentException("duplicate " + childName + " name: " + name);
            }
        }
        return result;
    }

    private static Map<String, Element> uniqueEffectChildren(Element effects) {
        Map<String, Element> result = new HashMap<>();
        for (Element child : directChildren(effects)) {
            String kind = localName(child);
            if (!"effect".equals(kind) && !"effectProxy".equals(kind)) {
                continue;
            }
            String name = requiredAttribute(child, "name");
            if (result.put(name, child) != null) {
                throw new IllegalArgumentException("duplicate effect name: " + name);
            }
        }
        return result;
    }

    private static void rejectAttributeCollision(
            Element parent, String childName, String attribute, String value, String allowedName) {
        for (Element child : directChildren(parent)) {
            if (childName.equals(localName(child))
                    && value.equalsIgnoreCase(child.getAttribute(attribute))
                    && !allowedName.equals(child.getAttribute("name"))) {
                throw new IllegalArgumentException(
                        "Echidna " + attribute + " is already registered under another name");
            }
        }
    }

    private static void rejectUnexpectedEchidnaReferences(Element root, Element effects) {
        Deque<Element> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Element element = pending.removeFirst();
            if (element == effects) {
                continue;
            }
            if (EFFECT_NAME.equals(element.getAttribute("effect"))
                            || IMPLEMENTATION_UUID.equalsIgnoreCase(element.getAttribute("uuid"))
                            || LIBRARY_NAME.equals(element.getAttribute("library"))) {
                throw new IllegalArgumentException(
                        "Echidna is referenced outside its effect registry (auto-apply refused)");
            }
            pending.addAll(directChildren(element));
        }
    }

    private static String requiredAttribute(Element element, String name) {
        if (!element.hasAttribute(name) || element.getAttribute(name).isEmpty()) {
            throw new IllegalArgumentException(
                    localName(element) + " is missing required attribute " + name);
        }
        return element.getAttribute(name);
    }

    private static List<Element> directChildren(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static Element createElementLike(Document document, Element root, String name) {
        String namespace = root.getNamespaceURI();
        return namespace == null ? document.createElement(name) : document.createElementNS(namespace, name);
    }

    private static String localName(Node node) {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    private static void validateSupportedNodes(Document document) {
        int elements = 0;
        NodeList children = document.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            switch (child.getNodeType()) {
                case Node.ELEMENT_NODE:
                    elements++;
                    validateElementNodes((Element) child);
                    break;
                case Node.COMMENT_NODE:
                    break;
                case Node.TEXT_NODE:
                    if (!child.getNodeValue().trim().isEmpty()) {
                        throw new IllegalArgumentException(
                                "non-whitespace text outside XML root is unsupported");
                    }
                    break;
                default:
                    throw new IllegalArgumentException(
                            "unsupported XML document node type: " + child.getNodeType());
            }
        }
        if (elements != 1) {
            throw new IllegalArgumentException("XML document must contain exactly one root element");
        }
    }

    private static void validateElementNodes(Element element) {
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            switch (child.getNodeType()) {
                case Node.ELEMENT_NODE:
                    validateElementNodes((Element) child);
                    break;
                case Node.COMMENT_NODE:
                case Node.TEXT_NODE:
                    break;
                default:
                    throw new IllegalArgumentException(
                            "unsupported XML node type under " + localName(element)
                                    + ": " + child.getNodeType());
            }
        }
    }

    private static String serialize(Document document) {
        StringWriter output = new StringWriter();
        output.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        NodeList children = document.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    || child.getNodeType() == Node.COMMENT_NODE) {
                writeNode(child, output, 0);
            }
        }
        return output.toString();
    }

    private static void writeNode(Node node, StringWriter output, int depth) {
        if (node.getNodeType() == Node.COMMENT_NODE) {
            indent(output, depth);
            output.append("<!--").append(node.getNodeValue()).append("-->\n");
            return;
        }
        if (node.getNodeType() != Node.ELEMENT_NODE) {
            throw new IllegalArgumentException(
                    "unsupported XML serialization node type: " + node.getNodeType());
        }
        Element element = (Element) node;
        indent(output, depth);
        output.append('<').append(element.getNodeName());
        NamedNodeMap attributes = element.getAttributes();
        List<Attr> sorted = new ArrayList<>();
        for (int index = 0; index < attributes.getLength(); index++) {
            sorted.add((Attr) attributes.item(index));
        }
        Collections.sort(sorted, Comparator.comparing(Attr::getName));
        for (Attr attribute : sorted) {
            output.append(' ').append(attribute.getName()).append("=\"")
                    .append(escapeXml(attribute.getValue(), true)).append('"');
        }

        List<Node> meaningful = new ArrayList<>();
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    || child.getNodeType() == Node.COMMENT_NODE
                    || (child.getNodeType() == Node.TEXT_NODE
                            && !child.getNodeValue().trim().isEmpty())) {
                meaningful.add(child);
            }
        }
        if (meaningful.isEmpty()) {
            output.append("/>\n");
            return;
        }
        boolean textOnly = meaningful.size() == 1
                && meaningful.get(0).getNodeType() == Node.TEXT_NODE;
        if (textOnly) {
            output.append('>').append(escapeXml(meaningful.get(0).getNodeValue(), false))
                    .append("</").append(element.getNodeName()).append(">\n");
            return;
        }
        output.append(">\n");
        for (Node child : meaningful) {
            if (child.getNodeType() == Node.TEXT_NODE) {
                indent(output, depth + 1);
                output.append(escapeXml(child.getNodeValue(), false)).append('\n');
            } else {
                writeNode(child, output, depth + 1);
            }
        }
        indent(output, depth);
        output.append("</").append(element.getNodeName()).append(">\n");
    }

    private static void indent(StringWriter output, int depth) {
        for (int index = 0; index < depth * 4; index++) {
            output.append(' ');
        }
    }

    private static String escapeXml(String value, boolean attribute) {
        String escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return attribute ? escaped.replace("\"", "&quot;").replace("'", "&apos;") : escaped;
    }

    private static Result mergeLegacy(String source, String libraryPath) {
        LegacyDocument document = LegacyDocument.parse(source);
        LegacyBlock libraries = document.requireTopLevel("libraries");
        LegacyBlock effects = document.requireTopLevel("effects");
        document.rejectRegistryDuplicates(libraries, "library");
        document.rejectRegistryDuplicates(effects, "effect");
        document.rejectAutoApplyReferences(effects);

        LegacyBlock library = libraries.directBlock(LIBRARY_NAME);
        LegacyBlock effect = effects.directBlock(EFFECT_NAME);
        boolean addLibrary = library == null;
        boolean addEffect = effect == null;
        if (library != null && !library.hasExactProperties(singleton("path", libraryPath))) {
            throw new IllegalArgumentException("conflicting Echidna legacy library registration");
        }
        if (effect != null && !effect.hasExactProperties(properties(
                "library", LIBRARY_NAME, "uuid", IMPLEMENTATION_UUID))) {
            throw new IllegalArgumentException("conflicting Echidna legacy effect registration");
        }
        libraries.rejectPropertyCollision("path", libraryPath, LIBRARY_NAME);
        effects.rejectPropertyCollision("uuid", IMPLEMENTATION_UUID, EFFECT_NAME);
        if (!addLibrary && !addEffect) {
            return new Result(source, false);
        }

        List<Insertion> insertions = new ArrayList<>();
        if (addLibrary) {
            String text = "  " + LIBRARY_NAME + " {\n"
                    + "    path " + libraryPath + "\n"
                    + "  }\n";
            insertions.add(new Insertion(libraries.closingOffset, text));
        }
        if (addEffect) {
            String text = "  " + EFFECT_NAME + " {\n"
                    + "    library " + LIBRARY_NAME + "\n"
                    + "    uuid " + IMPLEMENTATION_UUID + "\n"
                    + "  }\n";
            insertions.add(new Insertion(effects.closingOffset, text));
        }
        Collections.sort(insertions, (left, right) -> Integer.compare(right.offset, left.offset));
        StringBuilder merged = new StringBuilder(source);
        for (Insertion insertion : insertions) {
            merged.insert(insertion.offset, insertion.text);
        }
        return new Result(merged.toString(), true);
    }

    private static Map<String, String> singleton(String key, String value) {
        Map<String, String> result = new HashMap<>();
        result.put(key, value);
        return result;
    }

    private static Map<String, String> properties(String... pairs) {
        Map<String, String> result = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private static final class Insertion {
        final int offset;
        final String text;

        Insertion(int offset, String text) {
            this.offset = offset;
            this.text = text;
        }
    }

    private static final class LegacyDocument {
        final String source;
        final LegacyBlock root;

        LegacyDocument(String source, LegacyBlock root) {
            this.source = source;
            this.root = root;
        }

        static LegacyDocument parse(String source) {
            LegacyBlock root = new LegacyBlock("<root>", 0, source.length(), null);
            Deque<LegacyBlock> stack = new ArrayDeque<>();
            stack.push(root);
            int offset = 0;
            String[] lines = source.split("(?<=\\n)", -1);
            for (int lineNumber = 1; lineNumber <= lines.length; lineNumber++) {
                String raw = lines[lineNumber - 1];
                String logical = stripComment(raw).trim();
                if (!logical.isEmpty()) {
                    if (logical.endsWith("{")) {
                        String name = logical.substring(0, logical.length() - 1).trim();
                        requireWord(name, "block name", lineNumber);
                        LegacyBlock block = new LegacyBlock(name, offset, -1, stack.peek());
                        stack.peek().children.add(block);
                        stack.push(block);
                    } else if ("}".equals(logical)) {
                        if (stack.size() == 1) {
                            throw new IllegalArgumentException(
                                    "unexpected legacy closing brace at line " + lineNumber);
                        }
                        LegacyBlock closed = stack.pop();
                        closed.closingOffset = offset;
                        closed.endOffset = offset + raw.length();
                    } else if (logical.indexOf('{') >= 0 || logical.indexOf('}') >= 0) {
                        throw new IllegalArgumentException(
                                "ambiguous legacy brace syntax at line " + lineNumber);
                    } else {
                        int separator = firstWhitespace(logical);
                        if (separator <= 0 || separator == logical.length() - 1) {
                            throw new IllegalArgumentException(
                                    "legacy property requires name and value at line " + lineNumber);
                        }
                        String name = logical.substring(0, separator);
                        String value = logical.substring(separator).trim();
                        requireWord(name, "property name", lineNumber);
                        if (value.indexOf(' ') >= 0 || value.indexOf('\t') >= 0) {
                            throw new IllegalArgumentException(
                                    "ambiguous legacy property value at line " + lineNumber);
                        }
                        stack.peek().properties.add(new LegacyProperty(name, value));
                    }
                }
                offset += raw.length();
            }
            if (stack.size() != 1) {
                throw new IllegalArgumentException("unterminated legacy block: " + stack.peek().name);
            }
            return new LegacyDocument(source, root);
        }

        LegacyBlock requireTopLevel(String name) {
            LegacyBlock match = null;
            for (LegacyBlock block : root.children) {
                if (name.equals(block.name)) {
                    if (match != null) {
                        throw new IllegalArgumentException("duplicate legacy section: " + name);
                    }
                    match = block;
                }
            }
            if (match == null) {
                throw new IllegalArgumentException("missing legacy section: " + name);
            }
            return match;
        }

        void rejectRegistryDuplicates(LegacyBlock registry, String label) {
            Set<String> names = new HashSet<>();
            for (LegacyBlock child : registry.children) {
                if (!names.add(child.name)) {
                    throw new IllegalArgumentException(
                            "duplicate legacy " + label + " name: " + child.name);
                }
            }
        }

        void rejectAutoApplyReferences(LegacyBlock effectsRegistry) {
            rejectAutoApplyReferences(root, effectsRegistry);
        }

        private void rejectAutoApplyReferences(LegacyBlock block, LegacyBlock effectsRegistry) {
            if (block == effectsRegistry) {
                return;
            }
            for (LegacyProperty property : block.properties) {
                if (EFFECT_NAME.equals(property.value)
                        || IMPLEMENTATION_UUID.equalsIgnoreCase(property.value)
                        || LIBRARY_NAME.equals(property.value)) {
                    throw new IllegalArgumentException(
                            "Echidna is referenced outside its legacy effect registry "
                                    + "(auto-apply refused)");
                }
            }
            for (LegacyBlock child : block.children) {
                rejectAutoApplyReferences(child, effectsRegistry);
            }
        }

        private static String stripComment(String raw) {
            boolean quoted = false;
            for (int index = 0; index < raw.length(); index++) {
                char value = raw.charAt(index);
                if (value == '"' && (index == 0 || raw.charAt(index - 1) != '\\')) {
                    quoted = !quoted;
                }
                if (!quoted && value == '#') {
                    return raw.substring(0, index);
                }
                if (!quoted && value == '/' && index + 1 < raw.length()
                        && raw.charAt(index + 1) == '/') {
                    return raw.substring(0, index);
                }
            }
            if (quoted) {
                throw new IllegalArgumentException("unterminated quote in legacy config");
            }
            return raw;
        }

        private static int firstWhitespace(String value) {
            for (int index = 0; index < value.length(); index++) {
                if (Character.isWhitespace(value.charAt(index))) {
                    return index;
                }
            }
            return -1;
        }

        private static void requireWord(String value, String label, int line) {
            if (!value.matches("[A-Za-z0-9_.:-]+")) {
                throw new IllegalArgumentException(
                        "invalid legacy " + label + " at line " + line + ": " + value);
            }
        }
    }

    private static final class LegacyBlock {
        final String name;
        final int startOffset;
        int endOffset;
        int closingOffset;
        final LegacyBlock parent;
        final List<LegacyBlock> children = new ArrayList<>();
        final List<LegacyProperty> properties = new ArrayList<>();

        LegacyBlock(String name, int startOffset, int endOffset, LegacyBlock parent) {
            this.name = name;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.parent = parent;
        }

        LegacyBlock directBlock(String expected) {
            LegacyBlock result = null;
            for (LegacyBlock child : children) {
                if (expected.equals(child.name)) {
                    if (result != null) {
                        throw new IllegalArgumentException("duplicate legacy entry: " + expected);
                    }
                    result = child;
                }
            }
            return result;
        }

        boolean hasExactProperties(Map<String, String> expected) {
            if (!children.isEmpty() || properties.size() != expected.size()) {
                return false;
            }
            Map<String, String> actual = new HashMap<>();
            for (LegacyProperty property : properties) {
                if (actual.put(property.name, property.value) != null) {
                    return false;
                }
            }
            if (actual.containsKey("uuid")) {
                actual.put("uuid", actual.get("uuid").toLowerCase(Locale.ROOT));
                expected = new HashMap<>(expected);
                expected.put("uuid", expected.get("uuid").toLowerCase(Locale.ROOT));
            }
            return expected.equals(actual);
        }

        void rejectPropertyCollision(String propertyName, String value, String allowedName) {
            for (LegacyBlock child : children) {
                if (allowedName.equals(child.name)) {
                    continue;
                }
                for (LegacyProperty property : child.properties) {
                    if (propertyName.equals(property.name)
                            && value.equalsIgnoreCase(property.value)) {
                        throw new IllegalArgumentException(
                                "Echidna legacy " + propertyName
                                        + " is already registered under another name");
                    }
                }
            }
        }
    }

    private static final class LegacyProperty {
        final String name;
        final String value;

        LegacyProperty(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
