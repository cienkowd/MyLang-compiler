import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import java.io.*;

/**
 * Punkt wejścia kompilatora MyLang.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  PIPELINE KOMPILACJI
 * ═══════════════════════════════════════════════════════════════════
 *
 *   Plik .ml
 *      │
 *      ▼
 *   [1] CharStream  ← wczytanie pliku źródłowego
 *      │
 *      ▼
 *   [2] MyLangLexer ← tokenizacja (ANTLR4)
 *      │               błędy → MyLangErrorListener
 *      ▼
 *   [3] MyLangParser ← parsowanie → drzewo składniowe (AST)
 *      │                błędy → MyLangErrorListener
 *      ▼
 *   [4] LLVMActions.visit(tree) ← analiza semantyczna + generowanie IR
 *      │                          błędy → stderr, brak IR
 *      ▼
 *   [5] LLVMGenerator.generate() ← składanie pliku LLVM IR
 *      │
 *      ▼
 *   stdout: kompletny moduł LLVM IR
 *
 * Użycie:
 *   java -cp "antlr-4.13.1-complete.jar:build" Main program.ml > out.ll
 *   lli out.ll                   ← uruchomienie (wymaga LLVM)
 *   clang out.ll -o program      ← kompilacja do binarki
 *
 * Błędy są drukowane na stderr, kod LLVM IR na stdout.
 * Przy błędach exit code = 1 (brak wyjścia na stdout).
 */
public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Użycie: java -cp \"antlr.jar:build\" Main <plik.ml>");
            System.exit(1);
        }

        String inputFile = args[0];

        // ── Krok 1: wczytaj plik źródłowy ────────────────────────────────────
        CharStream input;
        try {
            input = CharStreams.fromFileName(inputFile);
        } catch (IOException e) {
            System.err.println("Błąd: nie można otworzyć pliku '" + inputFile + "'");
            System.exit(1);
            return;
        }

        // ── Krok 2: leksykalizacja ────────────────────────────────────────────
        // Lekser zamienia strumień znaków na tokeny (INT, ID, 'print', itp.)
        MyLangLexer lexer = new MyLangLexer(input);
        lexer.removeErrorListeners();               // usuń domyślny listener ANTLR
        MyLangErrorListener errorListener = new MyLangErrorListener();
        lexer.addErrorListener(errorListener);      // użyj własnego (po polsku)

        CommonTokenStream tokens = new CommonTokenStream(lexer);

        // ── Krok 3: parsowanie ────────────────────────────────────────────────
        // Parser tworzy drzewo składniowe (AST) zgodne z gramatyką MyLang.g4
        MyLangParser parser = new MyLangParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errorListener);

        ParseTree tree = parser.prog(); // zaczynamy od reguły startowej 'prog'

        // ── Krok 4: sprawdź błędy leksykalno-składniowe ───────────────────────
        if (errorListener.hasErrors()) {
            System.err.println("\nKompilacja przerwana – błędy składniowe.");
            System.exit(1);
        }

        // ── Krok 5: analiza semantyczna + generowanie IR ──────────────────────
        // LLVMActions odwiedza każdy węzeł AST w porządku głębokościowym,
        // sprawdza typy i generuje odpowiedni kod LLVM IR.
        // Wynik drukowany jest na stdout przez visitProg() po zakończeniu.
        LLVMActions actions = new LLVMActions();
        actions.visit(tree);

        if (actions.hasErrors()) {
            System.err.println("\nKompilacja przerwana – błędy semantyczne.");
            System.exit(1);
        }
        // visitProg wypisał już IR na stdout – kończymy z kodem 0
    }
}

// ════════════════════════════════════════════════════════════════════
//  WŁASNY LISTENER BŁĘDÓW ANTLR
// ════════════════════════════════════════════════════════════════════

/**
 * Zbiera i formatuje błędy leksykalne i składniowe generowane przez ANTLR4.
 *
 * ANTLR domyślnie drukuje komunikaty błędów w formacie angielskim i technicznym.
 * Ten listener zastępuje domyślny, drukując zrozumiałe komunikaty po polsku.
 *
 * Jest współdzielony przez Lexer i Parser (oba wywołują syntaxError).
 */
class MyLangErrorListener extends BaseErrorListener {

    private boolean hasErrors = false;

    /**
     * Wywoływane przez ANTLR przy każdym błędzie leksykalnym lub składniowym.
     *
     * @param recognizer         lekser lub parser (zależy od etapu)
     * @param offendingSymbol    token powodujący błąd (null przy błędzie leksera)
     * @param line               numer linii w pliku źródłowym
     * @param charPositionInLine pozycja w linii (od 0)
     * @param msg                techniczny komunikat ANTLR (w języku angielskim)
     * @param e                  wyjątek ANTLR (może być null)
     */
    @Override
    public void syntaxError(Recognizer<?, ?> recognizer,
                            Object offendingSymbol,
                            int line,
                            int charPositionInLine,
                            String msg,
                            RecognitionException e) {
        hasErrors = true;

        String where = "linia " + line + ":" + charPositionInLine;
        String what;

        if (offendingSymbol instanceof Token) {
            Token tok = (Token) offendingSymbol;
            if (tok.getType() == Token.EOF) {
                what = "nieoczekiwany koniec pliku";
            } else {
                what = "nieoczekiwany token '" + tok.getText() + "'";
            }
        } else {
            what = "nierozpoznany znak";
        }

        System.err.println("Błąd składniowy [" + where + "]: " + what);
        if (msg != null && !msg.isEmpty()) {
            System.err.println("  Szczegóły: " + msg);
        }
    }

    /** Czy zgłoszono jakikolwiek błąd? */
    public boolean hasErrors() {
        return hasErrors;
    }
}
