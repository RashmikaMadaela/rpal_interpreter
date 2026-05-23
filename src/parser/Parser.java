package parser;

import lexer.Token;
import lexer.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for the RPAL language.
 *
 * Implements the full phrase-structure grammar from RPAL_Grammar.pdf.
 * Left-recursive rules are converted to iterative loops.
 * Each grammar rule builds an {@link ASTNode} and returns it.
 *
 * Node-labelling convention (matching the grammar's "=> 'label'" annotations):
 *   Internal nodes  — type = grammar label (e.g. "let", "lambda", "+", "gamma")
 *   IDENTIFIER leaf — type = "ID",   value = name
 *   INTEGER    leaf — type = "INT",  value = decimal string
 *   STRING     leaf — type = "STR",  value = content (no surrounding quotes)
 *   true/false/nil/dummy — type = the keyword itself, value = null
 */
public class Parser {

    private List<Token> tokens;
    private int current;

    /**
     * Parses a complete token list and returns the root AST node.
     *
     * @param tokens list produced by {@link lexer.Lexer#tokenize()}
     * @return root of the AST
     * @throws RuntimeException on any syntax error
     */
    public ASTNode parse(List<Token> tokens) {
        this.tokens = tokens;
        this.current = 0;
        ASTNode root = parseE();
        if (currentToken().getType() != TokenType.EOF) {
            throw new RuntimeException(
                "Parser error: unexpected token after program end: " + currentToken());
        }
        return root;
    }

    // =========================================================================
    // Expressions
    // =========================================================================

    /**
     * E -> 'let' D 'in' E     => 'let'
     *    | 'fn' Vb+ '.' E     => 'lambda'
     *    | Ew
     */
    private ASTNode parseE() {
        if (isKeyword("let")) {
            consume();                        // consume 'let'
            ASTNode d = parseD();
            consumeKeyword("in");
            ASTNode e = parseE();
            ASTNode node = new ASTNode("let");
            node.addChild(d);
            node.addChild(e);
            return node;
        }

        if (isKeyword("fn")) {
            consume();                        // consume 'fn'
            // Collect one or more Vb
            List<ASTNode> vbs = new ArrayList<>();
            vbs.add(parseVb());
            while (isVbStart()) {
                vbs.add(parseVb());
            }
            consumeOperator(".");
            ASTNode e = parseE();
            // Build lambda(vb1, lambda(vb2, ... lambda(vbn, E)))
            // But per grammar, a single 'lambda' node holds all Vbs + body
            ASTNode lambda = new ASTNode("lambda");
            for (ASTNode vb : vbs) {
                lambda.addChild(vb);
            }
            lambda.addChild(e);
            return lambda;
        }

        return parseEw();
    }

    /**
     * Ew -> T 'where' Dr   => 'where'
     *     | T
     */
    private ASTNode parseEw() {
        ASTNode t = parseT();
        if (isKeyword("where")) {
            consume();                        // consume 'where'
            ASTNode dr = parseDr();
            ASTNode node = new ASTNode("where");
            node.addChild(t);
            node.addChild(dr);
            return node;
        }
        return t;
    }

    // =========================================================================
    // Tuple Expressions
    // =========================================================================

    /**
     * T -> Ta (',' Ta)+   => 'tau'
     *    | Ta
     */
    private ASTNode parseT() {
        ASTNode ta = parseTa();
        if (isPunctuation(",")) {
            ASTNode tau = new ASTNode("tau");
            tau.addChild(ta);
            while (isPunctuation(",")) {
                consume();                    // consume ','
                tau.addChild(parseTa());
            }
            return tau;
        }
        return ta;
    }

    /**
     * Ta -> Ta 'aug' Tc   => 'aug'    [left-recursive → loop]
     *     | Tc
     */
    private ASTNode parseTa() {
        ASTNode node = parseTc();
        while (isKeyword("aug")) {
            consume();                        // consume 'aug'
            ASTNode right = parseTc();
            ASTNode aug = new ASTNode("aug");
            aug.addChild(node);
            aug.addChild(right);
            node = aug;
        }
        return node;
    }

    /**
     * Tc -> B '->' Tc '|' Tc   => '->'
     *     | B
     */
    private ASTNode parseTc() {
        ASTNode b = parseB();
        if (isOperator("->")) {
            consume();                        // consume '->'
            ASTNode thenPart = parseTc();
            consumeOperator("|");
            ASTNode elsePart = parseTc();
            ASTNode node = new ASTNode("->");
            node.addChild(b);
            node.addChild(thenPart);
            node.addChild(elsePart);
            return node;
        }
        return b;
    }

    // =========================================================================
    // Boolean Expressions
    // =========================================================================

    /**
     * B -> B 'or' Bt   => 'or'    [left-recursive → loop]
     *    | Bt
     */
    private ASTNode parseB() {
        ASTNode node = parseBt();
        while (isKeyword("or")) {
            consume();                        // consume 'or'
            ASTNode right = parseBt();
            ASTNode or = new ASTNode("or");
            or.addChild(node);
            or.addChild(right);
            node = or;
        }
        return node;
    }

    /**
     * Bt -> Bt '&' Bs   => '&'    [left-recursive → loop]
     *     | Bs
     */
    private ASTNode parseBt() {
        ASTNode node = parseBs();
        while (isOperator("&")) {
            consume();                        // consume '&'
            ASTNode right = parseBs();
            ASTNode and = new ASTNode("&");
            and.addChild(node);
            and.addChild(right);
            node = and;
        }
        return node;
    }

    /**
     * Bs -> 'not' Bp   => 'not'
     *     | Bp
     */
    private ASTNode parseBs() {
        if (isKeyword("not")) {
            consume();                        // consume 'not'
            ASTNode bp = parseBp();
            ASTNode node = new ASTNode("not");
            node.addChild(bp);
            return node;
        }
        return parseBp();
    }

    /**
     * Bp -> A ('gr'|'>') A   => 'gr'
     *     | A ('ge'|'>=') A  => 'ge'
     *     | A ('ls'|'<') A   => 'ls'
     *     | A ('le'|'<=') A  => 'le'
     *     | A 'eq' A         => 'eq'
     *     | A 'ne' A         => 'ne'
     *     | A
     */
    private ASTNode parseBp() {
        ASTNode a = parseA();
        String label = null;

        if      (isKeyword("gr") || isOperator(">"))  { label = "gr"; }
        else if (isKeyword("ge") || isOperator(">=")) { label = "ge"; }
        else if (isKeyword("ls") || isOperator("<"))  { label = "ls"; }
        else if (isKeyword("le") || isOperator("<=")) { label = "le"; }
        else if (isKeyword("eq"))                     { label = "eq"; }
        else if (isKeyword("ne"))                     { label = "ne"; }

        if (label != null) {
            consume();                        // consume operator/keyword
            ASTNode right = parseA();
            ASTNode node = new ASTNode(label);
            node.addChild(a);
            node.addChild(right);
            return node;
        }
        return a;
    }

    // =========================================================================
    // Arithmetic Expressions
    // =========================================================================

    /**
     * A -> A '+' At  => '+'    [left-recursive → loop]
     *    | A '-' At  => '-'
     *    | '+' At               (unary +, no node wrapping)
     *    | '-' At    => 'neg'
     *    | At
     */
    private ASTNode parseA() {
        // Handle unary prefix operators
        if (isOperator("+")) {
            consume();
            return parseAt();             // unary '+' is identity, no node wrap
        }
        if (isOperator("-")) {
            consume();
            ASTNode at = parseAt();
            ASTNode neg = new ASTNode("neg");
            neg.addChild(at);
            return neg;
        }

        // Parse At, then loop for binary +/-
        ASTNode node = parseAt();
        while (isOperator("+") || isOperator("-")) {
            String op = currentToken().getValue();
            consume();
            ASTNode right = parseAt();
            ASTNode binop = new ASTNode(op);
            binop.addChild(node);
            binop.addChild(right);
            node = binop;
        }
        return node;
    }

    /**
     * At -> At '*' Af  => '*'    [left-recursive → loop]
     *     | At '/' Af  => '/'
     *     | Af
     */
    private ASTNode parseAt() {
        ASTNode node = parseAf();
        while (isOperator("*") || isOperator("/")) {
            String op = currentToken().getValue();
            consume();
            ASTNode right = parseAf();
            ASTNode binop = new ASTNode(op);
            binop.addChild(node);
            binop.addChild(right);
            node = binop;
        }
        return node;
    }

    /**
     * Af -> Ap '**' Af   => '**'    [right-recursive]
     *     | Ap
     */
    private ASTNode parseAf() {
        ASTNode ap = parseAp();
        if (isOperator("**")) {
            consume();
            ASTNode af = parseAf();       // right-recursive
            ASTNode node = new ASTNode("**");
            node.addChild(ap);
            node.addChild(af);
            return node;
        }
        return ap;
    }

    /**
     * Ap -> Ap '@' '<IDENTIFIER>' R   => '@'    [left-recursive → loop]
     *     | R
     */
    private ASTNode parseAp() {
        ASTNode node = parseR();
        while (isOperator("@")) {
            consume();                        // consume '@'
            if (!isIdentifier()) {
                throw new RuntimeException(
                    "Parser error: expected identifier after '@', got " + currentToken());
            }
            ASTNode id = new ASTNode("ID", currentToken().getValue());
            consume();
            ASTNode r = parseR();
            ASTNode at = new ASTNode("@");
            at.addChild(node);
            at.addChild(id);
            at.addChild(r);
            node = at;
        }
        return node;
    }

    // =========================================================================
    // Rators and Rands
    // =========================================================================

    /**
     * R -> R Rn   => 'gamma'    [left-recursive → loop]
     *    | Rn
     */
    private ASTNode parseR() {
        ASTNode node = parseRn();
        while (isRnStart()) {
            ASTNode rn = parseRn();
            ASTNode gamma = new ASTNode("gamma");
            gamma.addChild(node);
            gamma.addChild(rn);
            node = gamma;
        }
        return node;
    }

    /**
     * Rn -> '<IDENTIFIER>'
     *     | '<INTEGER>'
     *     | '<STRING>'
     *     | 'true'    => 'true'
     *     | 'false'   => 'false'
     *     | 'nil'     => 'nil'
     *     | '(' E ')'
     *     | 'dummy'   => 'dummy'
     */
    private ASTNode parseRn() {
        Token t = currentToken();

        if (t.getType() == TokenType.IDENTIFIER) {
            consume();
            return new ASTNode("ID", t.getValue());
        }
        if (t.getType() == TokenType.INTEGER) {
            consume();
            return new ASTNode("INT", t.getValue());
        }
        if (t.getType() == TokenType.STRING) {
            consume();
            return new ASTNode("STR", t.getValue());
        }
        if (t.getType() == TokenType.KEYWORD) {
            switch (t.getValue()) {
                case "true":  consume(); return new ASTNode("true");
                case "false": consume(); return new ASTNode("false");
                case "nil":   consume(); return new ASTNode("nil");
                case "dummy": consume(); return new ASTNode("dummy");
                default:
                    throw new RuntimeException(
                        "Parser error: unexpected keyword in Rn: " + t.getValue());
            }
        }
        if (t.getType() == TokenType.PUNCTUATION && t.getValue().equals("(")) {
            consume();                    // consume '('
            ASTNode e = parseE();
            consumePunctuation(")");
            return e;
        }

        throw new RuntimeException(
            "Parser error: unexpected token in Rn: " + t);
    }

    // =========================================================================
    // Definitions
    // =========================================================================

    /**
     * D -> Da 'within' D   => 'within'
     *    | Da
     */
    private ASTNode parseD() {
        ASTNode da = parseDa();
        if (isKeyword("within")) {
            consume();                        // consume 'within'
            ASTNode d = parseD();
            ASTNode node = new ASTNode("within");
            node.addChild(da);
            node.addChild(d);
            return node;
        }
        return da;
    }

    /**
     * Da -> Dr ('and' Dr)+   => 'and'
     *     | Dr
     */
    private ASTNode parseDa() {
        ASTNode dr = parseDr();
        if (isKeyword("and")) {
            ASTNode and = new ASTNode("and");
            and.addChild(dr);
            while (isKeyword("and")) {
                consume();                    // consume 'and'
                and.addChild(parseDr());
            }
            return and;
        }
        return dr;
    }

    /**
     * Dr -> 'rec' Db   => 'rec'
     *     | Db
     */
    private ASTNode parseDr() {
        if (isKeyword("rec")) {
            consume();                        // consume 'rec'
            ASTNode db = parseDb();
            ASTNode node = new ASTNode("rec");
            node.addChild(db);
            return node;
        }
        return parseDb();
    }

    /**
     * Db -> Vl '=' E               => '='
     *     | '<IDENTIFIER>' Vb+ '=' E => 'fcn_form'
     *     | '(' D ')'
     *
     * Disambiguation:
     *   - '(' starts a grouped definition
     *   - IDENTIFIER followed by ',' or '=' → Vl '=' E
     *   - IDENTIFIER followed by IDENTIFIER or '(' → fcn_form
     */
    private ASTNode parseDb() {
        // Grouped definition: '(' D ')'
        if (isPunctuation("(")) {
            consume();                        // consume '('
            ASTNode d = parseD();
            consumePunctuation(")");
            return d;
        }

        if (!isIdentifier()) {
            throw new RuntimeException(
                "Parser error: expected identifier or '(' in definition, got " + currentToken());
        }

        // Peek ahead to decide: Vl = E  vs  fcn_form
        // After identifier: ',' or '=' → Vl form; IDENTIFIER or '(' → fcn_form
        Token nextTok = peek(1);
        boolean isFcnForm = (nextTok.getType() == TokenType.IDENTIFIER)
            || (nextTok.getType() == TokenType.PUNCTUATION && nextTok.getValue().equals("("));

        if (isFcnForm) {
            // '<IDENTIFIER>' Vb+ '=' E => 'fcn_form'
            ASTNode id = new ASTNode("ID", currentToken().getValue());
            consume();                        // consume identifier
            ASTNode fcn = new ASTNode("fcn_form");
            fcn.addChild(id);
            // Parse one or more Vb
            fcn.addChild(parseVb());
            while (isVbStart()) {
                fcn.addChild(parseVb());
            }
            consumeOperator("=");
            fcn.addChild(parseE());
            return fcn;
        }

        // Vl '=' E => '='
        ASTNode vl = parseVl();
        consumeOperator("=");
        ASTNode e = parseE();
        ASTNode eq = new ASTNode("=");
        eq.addChild(vl);
        eq.addChild(e);
        return eq;
    }

    // =========================================================================
    // Variables
    // =========================================================================

    /**
     * Vb -> '<IDENTIFIER>'
     *     | '(' Vl ')'
     *     | '(' ')'   => '()'
     */
    private ASTNode parseVb() {
        if (isIdentifier()) {
            ASTNode id = new ASTNode("ID", currentToken().getValue());
            consume();
            return id;
        }

        if (isPunctuation("(")) {
            consume();                        // consume '('
            if (isPunctuation(")")) {
                consume();                    // consume ')'
                return new ASTNode("()");    // empty tuple parameter
            }
            ASTNode vl = parseVl();
            consumePunctuation(")");
            return vl;
        }

        throw new RuntimeException(
            "Parser error: expected identifier or '(' in Vb, got " + currentToken());
    }

    /**
     * Vl -> '<IDENTIFIER>' (',' '<IDENTIFIER>')*   => ','?
     *
     * Returns an IDENTIFIER leaf if there is only one identifier,
     * or a ',' node with all identifiers as children if there are two or more.
     */
    private ASTNode parseVl() {
        if (!isIdentifier()) {
            throw new RuntimeException(
                "Parser error: expected identifier in Vl, got " + currentToken());
        }
        List<ASTNode> ids = new ArrayList<>();
        ids.add(new ASTNode("ID", currentToken().getValue()));
        consume();

        while (isPunctuation(",")) {
            consume();                        // consume ','
            if (!isIdentifier()) {
                throw new RuntimeException(
                    "Parser error: expected identifier after ',' in Vl, got " + currentToken());
            }
            ids.add(new ASTNode("ID", currentToken().getValue()));
            consume();
        }

        if (ids.size() == 1) {
            return ids.get(0);
        }
        ASTNode comma = new ASTNode(",");
        for (ASTNode id : ids) {
            comma.addChild(id);
        }
        return comma;
    }

    // =========================================================================
    // Token-stream helpers
    // =========================================================================

    /** Returns the current token without consuming it. */
    private Token currentToken() {
        return tokens.get(current);
    }

    /**
     * Peeks {@code offset} positions ahead of current (0 = current token).
     * Returns the EOF token if the offset goes past the end.
     */
    private Token peek(int offset) {
        int idx = current + offset;
        if (idx >= tokens.size()) {
            return tokens.get(tokens.size() - 1); // EOF
        }
        return tokens.get(idx);
    }

    /** Consumes and returns the current token. */
    private Token consume() {
        Token t = tokens.get(current);
        current++;
        return t;
    }

    /** Consumes the current token and asserts it is the given keyword. */
    private void consumeKeyword(String kw) {
        Token t = currentToken();
        if (t.getType() != TokenType.KEYWORD || !t.getValue().equals(kw)) {
            throw new RuntimeException(
                "Parser error: expected keyword '" + kw + "', got " + t);
        }
        consume();
    }

    /** Consumes the current token and asserts it is the given operator. */
    private void consumeOperator(String op) {
        Token t = currentToken();
        if (t.getType() != TokenType.OPERATOR || !t.getValue().equals(op)) {
            throw new RuntimeException(
                "Parser error: expected operator '" + op + "', got " + t);
        }
        consume();
    }

    /** Consumes the current token and asserts it is the given punctuation. */
    private void consumePunctuation(String p) {
        Token t = currentToken();
        if (t.getType() != TokenType.PUNCTUATION || !t.getValue().equals(p)) {
            throw new RuntimeException(
                "Parser error: expected punctuation '" + p + "', got " + t);
        }
        consume();
    }

    // =========================================================================
    // Predicate helpers
    // =========================================================================

    private boolean isKeyword(String kw) {
        Token t = currentToken();
        return t.getType() == TokenType.KEYWORD && t.getValue().equals(kw);
    }

    private boolean isOperator(String op) {
        Token t = currentToken();
        return t.getType() == TokenType.OPERATOR && t.getValue().equals(op);
    }

    private boolean isPunctuation(String p) {
        Token t = currentToken();
        return t.getType() == TokenType.PUNCTUATION && t.getValue().equals(p);
    }

    private boolean isIdentifier() {
        return currentToken().getType() == TokenType.IDENTIFIER;
    }

    private boolean isInteger() {
        return currentToken().getType() == TokenType.INTEGER;
    }

    private boolean isString() {
        return currentToken().getType() == TokenType.STRING;
    }

    /**
     * Returns true if the current token can start a Vb:
     *   Vb -> IDENTIFIER | '(' ...
     */
    private boolean isVbStart() {
        return isIdentifier() || isPunctuation("(");
    }

    /**
     * Returns true if the current token can start an Rn (and therefore an R).
     *   Rn -> IDENTIFIER | INTEGER | STRING | true | false | nil | dummy | '('E')'
     */
    private boolean isRnStart() {
        Token t = currentToken();
        if (t.getType() == TokenType.IDENTIFIER) return true;
        if (t.getType() == TokenType.INTEGER)    return true;
        if (t.getType() == TokenType.STRING)     return true;
        if (t.getType() == TokenType.PUNCTUATION && t.getValue().equals("(")) return true;
        if (t.getType() == TokenType.KEYWORD) {
            switch (t.getValue()) {
                case "true": case "false": case "nil": case "dummy": return true;
                default: return false;
            }
        }
        return false;
    }
}
