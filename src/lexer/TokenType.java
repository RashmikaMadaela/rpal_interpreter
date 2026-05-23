package lexer;

/**
 * Enumeration of all token types produced by the RPAL lexer.
 * Based on RPAL_Lex.pdf lexical rules.
 */
public enum TokenType {
    IDENTIFIER,   // Letter (Letter | Digit | '_')*
    INTEGER,      // Digit+
    STRING,       // '...' (with escape sequences)
    OPERATOR,     // Operator_symbol+
    PUNCTUATION,  // '(' | ')' | ';' | ','
    KEYWORD,      // Reserved words: let, in, fn, where, aug, or, not, ...
    EOF           // End of input
}
