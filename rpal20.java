import csemachine.CSEMachine;
import lexer.Lexer;
import lexer.Token;
import parser.ASTNode;
import parser.Parser;
import standardizer.Standardizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Entry point for the RPAL interpreter.
 *
 * Usage:  java rpal20 <source_file>
 *
 * The interpreter executes the following pipeline:
 *   Source → Lexer → Token list → Parser → AST → Standardizer → ST → CSE Machine → Output
 */
public class rpal20 {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java rpal20 <source_file>");
            System.exit(1);
        }

        String filePath = args[0];
        String source;
        try {
            source = new String(Files.readAllBytes(Paths.get(filePath)));
        } catch (IOException e) {
            System.err.println("Error reading file '" + filePath + "': " + e.getMessage());
            System.exit(1);
            return;
        }

        try {
            // Phase 1: Lexical analysis
            Lexer lexer = new Lexer(source);
            List<Token> tokens = lexer.tokenize();

            // Phase 2: Parsing
            Parser parser = new Parser();
            ASTNode ast = parser.parse(tokens);

            // Phase 3: Standardization (returns a potentially new root node)
            Standardizer standardizer = new Standardizer();
            ASTNode st = standardizer.standardize(ast);

            // Phase 4: CSE Machine execution
            CSEMachine machine = new CSEMachine();
            machine.execute(st);

        } catch (Exception e) {
            System.err.println("Runtime error: " + e.getMessage());
            System.exit(1);
        }
    }
}
