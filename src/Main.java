import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

import java.io.*;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Uzycie: java -cp \"antlr.jar:build\" Main <plik.ml>");
            System.exit(1);
        }

        CharStream input;
        try {
            input = CharStreams.fromFileName(args[0]);
        } catch (IOException e) {
            System.err.println("Blad: nie mozna otworzyc pliku '" + args[0] + "'");
            System.exit(1);
            return;
        }

        ErrorListener errorListener = new ErrorListener();

        MyLangLexer lexer = new MyLangLexer(input);
        lexer.removeErrorListeners();
        lexer.addErrorListener(errorListener);

        MyLangParser parser = new MyLangParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(errorListener);

        ParseTree tree = parser.prog();

        if (errorListener.hasErrors()) {
            System.err.println("\nKompilacja przerwana.");
            System.exit(1);
        }

        LLVMActions actions = new LLVMActions();
        actions.visit(tree);

        if (actions.hasErrors()) {
            System.err.println("\nKompilacja przerwana.");
            System.exit(1);
        }
    }
}
