/**
 * Wynik ewaluacji wyrażenia podczas kompilacji.
 *
 * Łączy dwa elementy potrzebne do dalszego generowania kodu:
 *  - name  : gdzie w LLVM IR jest wynik (rejestr wirtualny lub stała)
 *  - type  : typ MyLang (potrzebny do wyboru właściwych instrukcji LLVM)
 *
 * Przykłady:
 *   new Value("%3",    VarType.INT)     – wynik w rejestrze %3 (np. po add)
 *   new Value("42",    VarType.INT)     – stała całkowita (przed załadowaniem)
 *   new Value("%7",    VarType.BOOL)    – wynik icmp w rejestrze %7
 *   new Value("%12",   VarType.STRING)  – wskaźnik i8* na ciąg znaków
 *   new Value("3.14",  VarType.REAL)    – stała zmiennoprzecinkowa
 *
 * Value jest obiektem niemutowalnym – każda operacja tworzy nowy Value.
 */
public class Value {

    /** Nazwa rejestru LLVM (np. "%5") lub literał (np. "42", "3.14", "true"). */
    public final String name;

    /** Typ MyLang wartości. Decyduje o wyborze instrukcji LLVM. */
    public final VarType type;

    public Value(String name, VarType type) {
        this.name = name;
        this.type = type;
    }

    @Override
    public String toString() {
        return "Value(" + name + " : " + type + ")";
    }
}
