package parser;

import java.util.ArrayList;
import java.util.List;

/**
 * A node in the Abstract Syntax Tree (AST) produced by the RPAL parser.
 *
 * Internal nodes have a non-null {@code type} (the grammar label, e.g. "let",
 * "lambda", "gamma", "+") and their children in {@code children}.
 *
 * Leaf nodes additionally carry a {@code value}:
 *   - IDENTIFIER leaves: type = "ID",      value = the name
 *   - INTEGER    leaves: type = "INT",      value = decimal string
 *   - STRING     leaves: type = "STR",      value = raw string content (no quotes)
 *   - TRUE/FALSE : type = "true"/"false",   value = same
 *   - NIL        : type = "nil"
 *   - DUMMY      : type = "dummy"
 */
public class ASTNode {

    /** The label / grammar operator for this node (e.g. "let", "gamma", "ID"). */
    private String type;

    /**
     * Leaf value (non-null for identifier, integer, string terminal nodes).
     * Null for purely structural nodes.
     */
    private String value;

    /** Ordered list of child nodes. Empty for leaves. */
    private List<ASTNode> children;

    /** Creates a structural (internal) node with the given label. */
    public ASTNode(String type) {
        this.type = type;
        this.value = null;
        this.children = new ArrayList<>();
    }

    /** Creates a leaf node with both a label and a value. */
    public ASTNode(String type, String value) {
        this.type = type;
        this.value = value;
        this.children = new ArrayList<>();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getType()                  { return type; }
    public void   setType(String type)       { this.type = type; }

    public String getValue()                 { return value; }
    public void   setValue(String value)     { this.value = value; }

    public List<ASTNode> getChildren()       { return children; }

    /** Appends a child at the end of this node's child list. */
    public void addChild(ASTNode child)      { children.add(child); }

    /** Convenience: number of direct children. */
    public int childCount()                  { return children.size(); }

    /** Returns child at index {@code i} (0-based). */
    public ASTNode child(int i)              { return children.get(i); }

    // -------------------------------------------------------------------------
    // Pretty-print helpers (used for debug / --ast flag)
    // -------------------------------------------------------------------------

    /**
     * Returns an indented textual representation of the subtree rooted here.
     * Matches the output format expected by reference rpal implementations:
     *   node labels are printed at their indentation level; leaves include
     *   their value.
     */
    public String prettyPrint() {
        StringBuilder sb = new StringBuilder();
        prettyPrint(sb, 0);
        return sb.toString();
    }

    private void prettyPrint(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append('.');
        sb.append(type);
        if (value != null) {
            sb.append('<').append(value).append('>');
        }
        sb.append('\n');
        for (ASTNode child : children) {
            child.prettyPrint(sb, depth + 1);
        }
    }

    @Override
    public String toString() {
        if (value != null) return type + "<" + value + ">";
        return type;
    }
}
