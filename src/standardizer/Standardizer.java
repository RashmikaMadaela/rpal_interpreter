package standardizer;

import parser.ASTNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts an RPAL Abstract Syntax Tree (AST) to a Standardized Tree (ST).
 *
 * Standardization is a bottom-up tree transformation that eliminates the
 * syntactic sugar of RPAL and produces a tree suitable for the CSE machine.
 *
 * Rules applied (based on RPAL specification):
 *
 *   let(=(x, E), P)               → gamma(lambda(x, P), E)
 *   where(P, =(x, E))             → gamma(lambda(x, P), E)
 *   fcn_form(f, V1, ..., Vn, E)   → =(f, lambda(V1, lambda(..., lambda(Vn, E)...)))
 *   lambda(V1, ..., Vn, E)        → lambda(V1, lambda(..., lambda(Vn, E)...))
 *   and(=(x1,E1), ..., =(xn,En))  → =(,(x1,...,xn), tau(E1,...,En))
 *   rec(=(x, E))                  → =(x, gamma(YSTAR, lambda(x, E)))
 *   within(=(x1,E1), =(x2,E2))    → =(x2, gamma(lambda(x1, E2), E1))
 */
public class Standardizer {

    /**
     * Entry point: standardize the entire AST.
     *
     * @param node the root of the AST (as produced by the Parser)
     * @return the root of the standardized tree (ST)
     */
    public ASTNode standardize(ASTNode node) {
        // Standardize children first (bottom-up)
        List<ASTNode> children = node.getChildren();
        for (int i = 0; i < children.size(); i++) {
            children.set(i, standardize(children.get(i)));
        }

        // Apply the standardization rule for this node
        switch (node.getType()) {
            case "let":      return standardizeLet(node);
            case "where":    return standardizeWhere(node);
            case "fcn_form": return standardizeFcnForm(node);
            case "lambda":   return standardizeLambda(node);
            case "and":      return standardizeAnd(node);
            case "rec":      return standardizeRec(node);
            case "within":   return standardizeWithin(node);
            default:         return node;
        }
    }

    // =========================================================================
    // Standardization rules
    // =========================================================================

    /**
     * let(=(x, E_def), E_body)  →  gamma(lambda(x, E_body), E_def)
     *
     * Child 0 must be a "=" node (after child standardization, an "and" or
     * "rec" definition will already have been converted to "=").
     */
    private ASTNode standardizeLet(ASTNode let) {
        ASTNode def  = let.child(0);   // "=" node: children are [lhs, rhs]
        ASTNode body = let.child(1);   // body expression

        if (!def.getType().equals("=")) {
            throw new RuntimeException(
                "Standardizer: 'let' expected '=' definition, got '" + def.getType() + "'");
        }

        ASTNode lambda = new ASTNode("lambda");
        lambda.addChild(def.child(0));  // the variable (or pattern)
        lambda.addChild(body);

        ASTNode gamma = new ASTNode("gamma");
        gamma.addChild(lambda);
        gamma.addChild(def.child(1));   // the defining expression
        return gamma;
    }

    /**
     * where(E_body, =(x, E_def))  →  gamma(lambda(x, E_body), E_def)
     *
     * Structurally identical to let but with the definition in child 1.
     */
    private ASTNode standardizeWhere(ASTNode where) {
        ASTNode body = where.child(0);  // expression
        ASTNode def  = where.child(1);  // "=" node

        if (!def.getType().equals("=")) {
            throw new RuntimeException(
                "Standardizer: 'where' expected '=' definition, got '" + def.getType() + "'");
        }

        ASTNode lambda = new ASTNode("lambda");
        lambda.addChild(def.child(0));
        lambda.addChild(body);

        ASTNode gamma = new ASTNode("gamma");
        gamma.addChild(lambda);
        gamma.addChild(def.child(1));
        return gamma;
    }

    /**
     * fcn_form(f, V1, ..., Vn, E)  →  =(f, lambda(V1, lambda(...lambda(Vn,E)...)))
     *
     * Children layout: [ID_f, Vb1, Vb2, ..., Vbn, E_body]
     */
    private ASTNode standardizeFcnForm(ASTNode fcn) {
        ASTNode fname = fcn.child(0);                       // ID<f>
        ASTNode body  = fcn.child(fcn.childCount() - 1);   // E

        // Build nested lambdas right-to-left over the Vb parameters
        for (int i = fcn.childCount() - 2; i >= 1; i--) {
            ASTNode lam = new ASTNode("lambda");
            lam.addChild(fcn.child(i));
            lam.addChild(body);
            body = lam;
        }

        ASTNode eq = new ASTNode("=");
        eq.addChild(fname);
        eq.addChild(body);
        return eq;
    }

    /**
     * lambda(V1, V2, ..., Vn, E)  →  lambda(V1, lambda(V2, ..., lambda(Vn,E)...))
     *
     * If the lambda has only 2 children (one Vb + body) it is already standard.
     */
    private ASTNode standardizeLambda(ASTNode lambda) {
        // Children: [Vb1, ..., Vbn, E_body]
        ASTNode body = lambda.child(lambda.childCount() - 1);

        // Build nested lambdas right-to-left
        for (int i = lambda.childCount() - 2; i >= 0; i--) {
            ASTNode lam = new ASTNode("lambda");
            lam.addChild(lambda.child(i));
            lam.addChild(body);
            body = lam;
        }
        // body is now lambda(Vb1, lambda(Vb2, ... lambda(Vbn, E)...))
        return body;
    }

    /**
     * and(=(x1,E1), ..., =(xn,En))  →  =(,(x1,...,xn), tau(E1,...,En))
     *
     * Each child of 'and' must be a "=" node at this point.
     */
    private ASTNode standardizeAnd(ASTNode and) {
        ASTNode comma = new ASTNode(",");
        ASTNode tau   = new ASTNode("tau");

        for (ASTNode child : and.getChildren()) {
            if (!child.getType().equals("=")) {
                throw new RuntimeException(
                    "Standardizer: 'and' expected '=' children, got '" + child.getType() + "'");
            }
            comma.addChild(child.child(0));  // variable
            tau.addChild(child.child(1));    // expression
        }

        ASTNode eq = new ASTNode("=");
        eq.addChild(comma);
        eq.addChild(tau);
        return eq;
    }

    /**
     * rec(=(x, E))  →  =(x, gamma(YSTAR, lambda(x, E)))
     *
     * YSTAR is a special node recognised by the CSE machine as the Y* combinator.
     */
    private ASTNode standardizeRec(ASTNode rec) {
        ASTNode eq  = rec.child(0);  // must be "="
        if (!eq.getType().equals("=")) {
            throw new RuntimeException(
                "Standardizer: 'rec' expected '=' child, got '" + eq.getType() + "'");
        }
        ASTNode x = eq.child(0);    // the recursively-defined name
        ASTNode e = eq.child(1);    // the defining expression (a lambda)

        // lambda(x, E)  — x appears as the bound variable in this lambda
        ASTNode lam = new ASTNode("lambda");
        lam.addChild(deepCopy(x));  // deep-copy so x can appear in two places
        lam.addChild(e);

        // gamma(YSTAR, lambda(x, E))
        ASTNode gamma = new ASTNode("gamma");
        gamma.addChild(new ASTNode("YSTAR"));
        gamma.addChild(lam);

        // =(x, gamma(YSTAR, lambda(x, E)))
        ASTNode newEq = new ASTNode("=");
        newEq.addChild(x);
        newEq.addChild(gamma);
        return newEq;
    }

    /**
     * within(=(x1,E1), =(x2,E2))  →  =(x2, gamma(lambda(x1,E2), E1))
     */
    private ASTNode standardizeWithin(ASTNode within) {
        ASTNode d1 = within.child(0);   // =(x1, E1)
        ASTNode d2 = within.child(1);   // =(x2, E2)

        if (!d1.getType().equals("=") || !d2.getType().equals("=")) {
            throw new RuntimeException(
                "Standardizer: 'within' expected two '=' children");
        }

        ASTNode x1 = d1.child(0);
        ASTNode e1 = d1.child(1);
        ASTNode x2 = d2.child(0);
        ASTNode e2 = d2.child(1);

        ASTNode lam = new ASTNode("lambda");
        lam.addChild(x1);
        lam.addChild(e2);

        ASTNode gamma = new ASTNode("gamma");
        gamma.addChild(lam);
        gamma.addChild(e1);

        ASTNode eq = new ASTNode("=");
        eq.addChild(x2);
        eq.addChild(gamma);
        return eq;
    }

    // =========================================================================
    // Utility
    // =========================================================================

    /**
     * Deep-copies a subtree so that the same variable node can appear in two
     * locations without aliasing issues (needed for the 'rec' rule where the
     * bound variable appears both in the '=' LHS and the lambda parameter).
     */
    private ASTNode deepCopy(ASTNode node) {
        ASTNode copy = new ASTNode(node.getType(), node.getValue());
        for (ASTNode child : node.getChildren()) {
            copy.addChild(deepCopy(child));
        }
        return copy;
    }
}
