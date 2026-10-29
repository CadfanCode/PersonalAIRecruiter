package app.jobpilot.apply;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The page as Playwright's accessibility snapshot describes it (the same view Playwright MCP gives a model): every element
 * with a role, a name and a {@code ref}. This class parses it, builds the {@link FormField}s the validator already understands,
 * and produces the text that may go into a prompt. That text never carries what a text box holds: a password, a personal
 * number or anything the person typed is replaced by {@code [filled]} before the snapshot leaves this class.
 */
public final class PageSnapshot {

    /** One line of the snapshot. {@code value} is the real text of a text box and stays inside the process. */
    public record Node(String ref, String role, String name, String value, Set<String> flags, int depth, int index, String group,
                       String before, String href, List<String> options) {

        boolean has(String flag) {
            return flags.contains(flag);
        }
    }

    /** Roles that take input. */
    private static final Set<String> TEXT_ROLES = Set.of("textbox", "searchbox", "spinbutton");
    private static final Set<String> CHOICE_ROLES = Set.of("checkbox", "radio", "switch");
    private static final Set<String> SELECT_ROLES = Set.of("combobox", "listbox");
    private static final Set<String> CLICK_ROLES = Set.of("button", "link", "menuitem", "tab");
    private static final Set<String> GROUP_ROLES = Set.of("group", "radiogroup", "fieldset", "form", "region", "dialog", "alertdialog");
    private static final int MAX_CHARS = 40_000;

    private static final Pattern LINE = Pattern.compile("^(\\s*)- ([\\w/-]+)(?: \"((?:[^\"\\\\]|\\\\.)*)\")?((?: \\[[^\\]]*\\])*):?(?: (.*))?$");
    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]*)\\]");
    private static final Pattern REF = Pattern.compile("^ref=((?:f\\d+)?e\\d+)$");
    /** What a model may name as a target. Checked before the text is ever used to build a locator. */
    public static final Pattern SAFE_REF = Pattern.compile("^(f\\d+)?e\\d+$");

    private final String text;
    private final List<Node> nodes;
    private final Map<String, Node> byRef = new LinkedHashMap<>();
    private final String prompt;

    private PageSnapshot(String text, List<Node> nodes, String prompt) {
        this.text = text;
        this.nodes = nodes;
        this.prompt = prompt;
        for (Node n : nodes) {
            if (n.ref() != null) {
                byRef.put(n.ref(), n);
            }
        }
    }

    public static PageSnapshot parse(String raw) {
        String source = raw == null ? "" : raw;
        List<Node> nodes = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        String[] lines = source.split("\n", -1);
        List<Node> stack = new ArrayList<>(); // ancestors by depth
        String lastText = "";
        int lastTextDepth = -1;
        int skipDeeperThan = -1; // continuation lines of a multi-line text box value
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = LINE.matcher(line);
            int indent = line.length() - line.stripLeading().length();
            if (skipDeeperThan >= 0) {
                if (indent > skipDeeperThan && !line.stripLeading().startsWith("- ")) {
                    continue;
                }
                skipDeeperThan = -1;
            }
            if (!m.matches()) {
                if (!line.isBlank()) {
                    out.append(line).append('\n');
                }
                continue;
            }
            int depth = m.group(1).length() / 2;
            String role = m.group(2);
            String name = m.group(3) == null ? "" : m.group(3).replace("\\\"", "\"").replace("\\\\", "\\");
            String rest = m.group(5) == null ? "" : m.group(5);
            Set<String> flags = new java.util.LinkedHashSet<>();
            String ref = null;
            Matcher b = BRACKET.matcher(m.group(4) == null ? "" : m.group(4));
            while (b.find()) {
                Matcher r = REF.matcher(b.group(1));
                if (r.matches()) {
                    ref = r.group(1);
                } else {
                    flags.add(b.group(1).toLowerCase(Locale.ROOT));
                }
            }
            while (!stack.isEmpty() && stack.get(stack.size() - 1).depth() >= depth) {
                stack.remove(stack.size() - 1);
            }
            if (role.equals("text")) {
                lastText = rest.isBlank() ? name : rest;
                lastTextDepth = depth;
            }
            if (role.equals("/url")) {
                // the address of the link above it
                for (int k = nodes.size() - 1; k >= 0; k--) {
                    Node link = nodes.get(k);
                    if (link.role().equals("link") && link.href() == null) {
                        nodes.set(k, new Node(link.ref(), link.role(), link.name(), link.value(), link.flags(), link.depth(), link.index(),
                                link.group(), link.before(), name.isEmpty() ? rest.replaceAll("^\"|\"$", "") : name, link.options()));
                        break;
                    }
                    if (link.depth() < depth - 1) {
                        break;
                    }
                }
                out.append(line).append('\n');
                continue;
            }
            String group = "";
            for (int k = stack.size() - 1; k >= 0; k--) {
                Node a = stack.get(k);
                if ((GROUP_ROLES.contains(a.role()) || a.role().equals("heading")) && !a.name().isBlank()) {
                    group = a.name();
                    break;
                }
            }
            String before = lastTextDepth == depth || lastTextDepth == depth + 1 ? lastText : "";
            if (role.equals("option") && !stack.isEmpty()) {
                // an option belongs to the list or drop-down above it
                for (int k = stack.size() - 1; k >= 0; k--) {
                    Node owner = stack.get(k);
                    if (SELECT_ROLES.contains(owner.role())) {
                        owner.options().add(name);
                        break;
                    }
                }
            }
            boolean typed = TEXT_ROLES.contains(role);
            Node node = new Node(ref, role, name, rest, flags, depth, nodes.size(), group, before, null, new ArrayList<>());
            nodes.add(node);
            stack.add(node);
            if (typed && !rest.isBlank()) {
                // the real value stays in the node; the prompt only learns that the box is not empty
                out.append(line, 0, line.length() - rest.length()).append("[filled]\n");
                skipDeeperThan = indent;
            } else {
                out.append(line).append('\n');
            }
        }
        String prompt = out.length() > MAX_CHARS ? out.substring(0, MAX_CHARS) + "\n[the page continues; the rest is not shown]\n" : out.toString();
        return new PageSnapshot(source, nodes, prompt);
    }

    /** The text for the prompt: structure, names and refs, but no text box contents. */
    public String forPrompt() {
        return prompt;
    }

    /** Whole-page equality for "did anything change": values count, so it compares the raw text. */
    public boolean sameAs(PageSnapshot other) {
        return other != null && stripRefs(text).equals(stripRefs(other.text));
    }

    private static String stripRefs(String s) {
        return s.replaceAll("\\[ref=[^\\]]*\\]", "").replaceAll("\\[active\\]", "");
    }

    public Node node(String ref) {
        return ref == null ? null : byRef.get(ref);
    }

    public List<Node> nodes() {
        return nodes;
    }

    /** Controls that take input, as the validator sees them. Ids are refs, valid until the page changes. */
    public List<FormField> fields() {
        List<FormField> out = new ArrayList<>();
        for (Node n : nodes) {
            if (n.ref() == null) {
                continue;
            }
            FormField f = fieldOf(n);
            if (f != null) {
                out.add(f);
            }
        }
        return out;
    }

    /** Links and buttons, with their names; the targets a click may name. */
    public List<Node> clickables() {
        return nodes.stream().filter(n -> n.ref() != null && CLICK_ROLES.contains(n.role())).toList();
    }

    public boolean mentions(Pattern pattern) {
        return pattern.matcher(text).find();
    }

    public FormField field(String ref) {
        Node n = node(ref);
        return n == null ? null : fieldOf(n);
    }

    private static FormField fieldOf(Node n) {
        String role = n.role();
        boolean disabled = n.has("disabled");
        if (TEXT_ROLES.contains(role)) {
            String type = role.equals("spinbutton") ? "number" : role.equals("searchbox") ? "search" : "text";
            return new FormField(n.ref(), 0, "input", type, "", n.name(), "", "", "", n.group(), n.before(), "", "",
                    n.has("required"), disabled, true, valueOf(n), false, false, "", false, List.of());
        }
        if (CHOICE_ROLES.contains(role)) {
            String type = role.equals("radio") ? "radio" : "checkbox";
            return new FormField(n.ref(), 0, "input", type, "", n.name(), "", "", "", n.group(), n.before(), n.name(),
                    "", n.has("required"), disabled, true, n.name(), n.has("checked"), false, "", false, List.of());
        }
        if (SELECT_ROLES.contains(role) || (role.equals("button") && n.has("expanded")) || n.has("haspopup")) {
            List<FormField.Option> options = n.options().stream().map(o -> new FormField.Option(o, o)).toList();
            return new FormField(n.ref(), 0, "select", "select", "", n.name(), "", "", "", n.group(), n.before(), "", "",
                    n.has("required"), disabled, true, valueOf(n), false, false, "", false, options);
        }
        return null;
    }

    private static String valueOf(Node n) {
        String v = n.value() == null ? "" : n.value().strip();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.equals("[filled]") ? "" : v;
    }
}
