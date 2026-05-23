package lexer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lexical analyser for the RPAL language.
 *
 * Implements the full lexical grammar from RPAL_Lex.pdf:
 *   Identifier -> Letter (Letter | Digit | '_')*
 *   Integer    -> Digit+
 *   Operator   -> Operator_symbol+
 *   String     -> '\'' ( escape | char )* '\''
 *   Spaces     -> (space | ht | Eol)+   => deleted
 *   Comment    -> '//' ... Eol          => deleted
 *   Punction   -> '(' | ')' | ';' | ','
 *
 * Keywords are identifiers that match one of RPAL's reserved words and are
 * returned as TokenType.KEYWORD instead of TokenType.IDENTIFIER.
 */
public class Lexer {

    /** All RPAL reserved keywords. */
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "let", "in", "fn", "where", "aug", "or", "not",
        "gr", "ge", "ls", "le", "eq", "ne",
        "true", "false", "nil", "dummy",
        "within", "and", "rec"
    ));

    private final String input;
    private int pos;
    private final int length;

    /**
     * Creates a lexer for the given RPAL source string.
     *
     * @param input the complete RPAL program text
     */
    public Lexer(String input) {
        this.input = input;
        this.pos = 0;
        this.length = input.length();
    }

    /**
     * Tokenises the entire input and returns the token list.
     * Whitespace and comments are silently discarded.
     * The list always ends with a single EOF token.
     *
     * @return ordered list of tokens
     * @throws RuntimeException on any lexical error
     */
    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (pos < length) {
            Token t = nextToken();
            if (t != null) {
                tokens.add(t);
            }
        }
        tokens.add(new Token(TokenType.EOF, ""));
        return tokens;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Reads and returns the next token, or null for whitespace/comments. */
    private Token nextToken() {
        char c = peek();

        // Whitespace (space, horizontal-tab, carriage-return, newline) => DELETE
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
            skipWhitespace();
            return null;
        }

        // Comment: starts with '//' => DELETE rest of line
        if (c == '/' && pos + 1 < length && input.charAt(pos + 1) == '/') {
            skipComment();
            return null;
        }

        // Identifier or keyword: starts with a letter
        if (Character.isLetter(c)) {
            return readIdentifier();
        }

        // Integer: starts with a digit
        if (Character.isDigit(c)) {
            return readInteger();
        }

        // String: starts with a single quote
        if (c == '\'') {
            return readString();
        }

        // Punctuation: ( ) ; ,
        if (c == '(' || c == ')' || c == ';' || c == ',') {
            pos++;
            return new Token(TokenType.PUNCTUATION, String.valueOf(c));
        }

        // Operator: one or more operator symbols
        if (isOperatorSymbol(c)) {
            return readOperator();
        }

        throw new RuntimeException(
            "Lexer error: unexpected character '" + c + "' (\\u"
            + String.format("%04x", (int) c) + ") at position " + pos);
    }

    /** Returns the character at the current position without advancing. */
    private char peek() {
        return input.charAt(pos);
    }

    /** Skips all consecutive whitespace characters. */
    private void skipWhitespace() {
        while (pos < length) {
            char c = input.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else {
                break;
            }
        }
    }

    /**
     * Skips a '//' comment to the end of the current line.
     * The newline itself is consumed by the next skipWhitespace call.
     */
    private void skipComment() {
        // skip '//'
        pos += 2;
        while (pos < length && input.charAt(pos) != '\n' && input.charAt(pos) != '\r') {
            pos++;
        }
    }

    /**
     * Reads an RPAL Identifier or Keyword token.
     * Grammar: Letter (Letter | Digit | '_')*
     */
    private Token readIdentifier() {
        StringBuilder sb = new StringBuilder();
        // First character is guaranteed to be a letter (caller checked)
        while (pos < length) {
            char c = input.charAt(pos);
            if (Character.isLetter(c) || Character.isDigit(c) || c == '_') {
                sb.append(c);
                pos++;
            } else {
                break;
            }
        }
        String value = sb.toString();
        TokenType type = KEYWORDS.contains(value) ? TokenType.KEYWORD : TokenType.IDENTIFIER;
        return new Token(type, value);
    }

    /**
     * Reads an RPAL Integer token.
     * Grammar: Digit+
     */
    private Token readInteger() {
        StringBuilder sb = new StringBuilder();
        while (pos < length && Character.isDigit(input.charAt(pos))) {
            sb.append(input.charAt(pos++));
        }
        return new Token(TokenType.INTEGER, sb.toString());
    }

    /**
     * Reads an RPAL String token (with escape-sequence processing).
     * Grammar: '\'' ( '\t' | '\n' | '\\' | '\'' | printable )* '\''
     *
     * The stored value has escapes already resolved; quotes are not included.
     */
    private Token readString() {
        pos++; // consume opening '
        StringBuilder sb = new StringBuilder();
        while (pos < length && input.charAt(pos) != '\'') {
            char c = input.charAt(pos);
            if (c == '\\') {
                pos++;
                if (pos >= length) {
                    throw new RuntimeException("Lexer error: unterminated escape sequence in string");
                }
                char esc = input.charAt(pos++);
                switch (esc) {
                    case 't':  sb.append('\t'); break;
                    case 'n':  sb.append('\n'); break;
                    case '\\': sb.append('\\'); break;
                    case '\'': sb.append('\''); break;
                    default:
                        throw new RuntimeException(
                            "Lexer error: invalid escape sequence '\\" + esc + "' in string");
                }
            } else {
                sb.append(c);
                pos++;
            }
        }
        if (pos >= length) {
            throw new RuntimeException("Lexer error: unterminated string literal");
        }
        pos++; // consume closing '
        return new Token(TokenType.STRING, sb.toString());
    }

    /**
     * Reads an RPAL Operator token.
     * Grammar: Operator_symbol+
     * Note: ',' '(' ')' ';' are punctuation and excluded from operators.
     */
    private Token readOperator() {
        StringBuilder sb = new StringBuilder();
        while (pos < length && isOperatorSymbol(input.charAt(pos))) {
            sb.append(input.charAt(pos++));
        }
        return new Token(TokenType.OPERATOR, sb.toString());
    }

    /**
     * Returns true if the character is an RPAL operator symbol.
     * From RPAL_Lex.pdf:
     *   Operator_symbol -> '+' | '-' | '*' | '<' | '>' | '&' | '.'
     *                    | '@' | '/' | ':' | '=' | '~' | '|' | '$'
     *                    | '!' | '#' | '%' | '^' | '_' | '[' | ']'
     *                    | '{' | '}' | '"' | '?' 
     * Note: single-quote "'" is an operator_symbol in the spec but is consumed
     * by readString() before this method is ever called for an opening quote.
     */
    private boolean isOperatorSymbol(char c) {
        switch (c) {
            case '+': case '-': case '*': case '<': case '>': case '&':
            case '.': case '@': case '/': case ':': case '=': case '~':
            case '|': case '$': case '!': case '#': case '%': case '^':
            case '_': case '[': case ']': case '{': case '}': case '"':
            case '?':
                return true;
            default:
                return false;
        }
    }
}
