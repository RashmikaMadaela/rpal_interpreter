package lexer;

/**
 * Represents a single lexical token produced by the RPAL lexer.
 * Each token has a type and a string value.
 */
public class Token {
    private final TokenType type;
    private final String value;

    /**
     * Constructs a Token with the given type and value.
     *
     * @param type  the category of this token
     * @param value the literal text of this token
     */
    public Token(TokenType type, String value) {
        this.type = type;
        this.value = value;
    }

    /** Returns the token type. */
    public TokenType getType() {
        return type;
    }

    /** Returns the token's string value. */
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "Token(" + type + ", \"" + value + "\")";
    }
}
