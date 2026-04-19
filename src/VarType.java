/**
 * Typy danych języka MyLang wraz z odpowiadającymi im typami LLVM IR.
 *
 * Tabela mapowania:
 * ┌──────────┬──────────────────────────────────┬──────────┐
 * │ MyLang   │ Opis                             │ LLVM IR  │
 * ├──────────┼──────────────────────────────────┼──────────┤
 * │ int      │ Liczba całkowita 32-bitowa        │ i32      │
 * │ real     │ Zmiennoprzecinkowa 64-bit (alias) │ double   │
 * │ float32  │ Zmiennoprzecinkowa 32-bit IEEE754 │ float    │
 * │ float64  │ Zmiennoprzecinkowa 64-bit IEEE754 │ double   │
 * │ bool     │ Wartość logiczna (true/false)     │ i1       │
 * │ string   │ Ciąg znaków (max 255 znaków)      │ i8*      │
 * └──────────┴──────────────────────────────────┴──────────┘
 *
 * Uwaga: real i float64 są synonimami na poziomie LLVM IR (oba → double),
 * ale rozróżniamy je na poziomie MyLang dla czytelności kodu użytkownika.
 */
public enum VarType {
    INT,
    REAL,
    FLOAT32,
    FLOAT64,
    BOOL,
    STRING,
    /** Typ wewnętrzny – używany przy błędach parsowania. */
    UNKNOWN;

    // ─── Konwersja do LLVM IR ────────────────────────────────────────────────

    /**
     * Zwraca nazwę odpowiadającego typu w LLVM IR.
     * Używane przy generowaniu instrukcji: alloca, load, store, fadd, itp.
     */
    public String llvm() {
        switch (this) {
            case INT:     return "i32";
            case REAL:
            case FLOAT64: return "double";
            case FLOAT32: return "float";
            case BOOL:    return "i1";
            case STRING:  return "i8*";
            default:      return "void";
        }
    }

    // ─── Predykaty ────────────────────────────────────────────────────────────

    /**
     * Czy typ jest zmiennoprzecinkowy?
     * Używane do wyboru instrukcji LLVM: fadd/fsub/fmul/fdiv zamiast add/sub/mul/sdiv.
     */
    public boolean isFloat() {
        return this == REAL || this == FLOAT32 || this == FLOAT64;
    }

    /**
     * Czy typ jest liczbowy (int lub float)?
     * Używane do walidacji operandów operatorów arytmetycznych i porównań.
     */
    public boolean isNumeric() {
        return this == INT || isFloat();
    }

    // ─── Czytelna reprezentacja ───────────────────────────────────────────────

    /** Nazwa typu w składni MyLang (używana w komunikatach błędów). */
    @Override
    public String toString() {
        switch (this) {
            case INT:     return "int";
            case REAL:    return "real";
            case FLOAT32: return "float32";
            case FLOAT64: return "float64";
            case BOOL:    return "bool";
            case STRING:  return "string";
            default:      return "unknown";
        }
    }
}
