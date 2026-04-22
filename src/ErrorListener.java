import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;

public class ErrorListener extends BaseErrorListener {

    private boolean hasErrors = false;

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer,
                            Object offendingSymbol,
                            int line,
                            int charPositionInLine,
                            String msg,
                            RecognitionException e) {
        hasErrors = true;

        String location = "linia " + line + ":" + charPositionInLine;
        String description;

        if (offendingSymbol instanceof Token) {
            Token token = (Token) offendingSymbol;
            description = token.getType() == Token.EOF
                ? "nieoczekiwany koniec pliku"
                : "nieoczekiwany token '" + token.getText() + "'";
        } else {
            description = "nierozpoznany znak";
        }

        System.err.println("Blad skladniowy [" + location + "]: " + description);
        if (msg != null && !msg.isEmpty()) {
            System.err.println("  " + msg);
        }
    }

    public boolean hasErrors() {
        return hasErrors;
    }
}
