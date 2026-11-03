package io.github.qingyicode.repopilot.dependency;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.helpers.DefaultHandler;

/** Secure XML tree with actual source positions; no external entity or schema resolution. */
public final class PomXml {
    private PomXml() {}
    public static final class Node {
        public final String name;
        public final int start;
        public int end;
        private final StringBuilder text = new StringBuilder();
        public final List<Node> children = new ArrayList<>();
        Node(String name, int start) { this.name = name; this.start = start; }
        public Node child(String name) { return children.stream().filter(n -> n.name.equals(name)).findFirst().orElse(null); }
        public List<Node> children(String name) { return children.stream().filter(n -> n.name.equals(name)).toList(); }
        public String value() { return text.toString().trim(); }
        public String value(String name, String fallback) { var n = child(name); return n == null ? fallback : n.value(); }
    }
    public static Node parse(String xml) {
        try {
            var factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var handler = new DefaultHandler() {
                Locator locator; Node root; final ArrayDeque<Node> stack = new ArrayDeque<>();
                @Override public void setDocumentLocator(Locator value) { locator = value; }
                @Override public void startElement(String uri, String local, String qName, Attributes attributes) {
                    var node = new Node(local.isBlank() ? qName : local, locator.getLineNumber());
                    if (stack.isEmpty()) root = node; else stack.peek().children.add(node);
                    stack.push(node);
                }
                @Override public void characters(char[] ch, int start, int length) { if (!stack.isEmpty()) stack.peek().text.append(ch, start, length); }
                @Override public void endElement(String uri, String local, String qName) { stack.pop().end = locator.getLineNumber(); }
            };
            factory.newSAXParser().parse(new InputSource(new StringReader(xml)), handler);
            if (handler.root == null || !handler.root.name.equals("project")) throw new IllegalArgumentException("Not a Maven POM");
            return handler.root;
        } catch (Exception error) { throw new IllegalArgumentException("INVALID_OR_UNSAFE_POM"); }
    }
}
