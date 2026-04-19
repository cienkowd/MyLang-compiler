/**
 * Metadane tablicy zarejestrowanej w tablicy symboli kompilatora MyLang.
 *
 * Przechowuje informacje potrzebne do generowania poprawnego kodu LLVM IR
 * dla operacji na tablicach: deklaracji, odczytu i zapisu elementów.
 *
 * Składnia MyLang → pola ArrayInfo:
 * ┌─────────────────────┬─────────────┬──────────────────────────┐
 * │ Deklaracja MyLang   │ startIndex  │ size / endIndex          │
 * ├─────────────────────┼─────────────┼──────────────────────────┤
 * │ int[5] arr          │ 0           │ size=5, endIndex=4       │
 * │ int[1..5] arr       │ 1           │ size=5, endIndex=5       │
 * │ int[10..20] arr     │ 10          │ size=11, endIndex=20     │
 * └─────────────────────┴─────────────┴──────────────────────────┘
 *
 * Generowany LLVM IR dla tablicy:
 *   %arr = alloca [size x elementType.llvm()]
 *
 * Dostęp do elementu arr[i] (po korekcji indeksu: adj = i - startIndex):
 *   %gep = getelementptr inbounds [size x T], [size x T]* %arr, i32 0, i32 adj
 *   %val = load T, T* %gep
 */
public class ArrayInfo {

    /** Typ elementów tablicy. Wszystkie elementy mają ten sam typ. */
    public final VarType elementType;

    /** Pierwszy dopuszczalny indeks (inkluzywny). Dla int[N] arr = 0. */
    public final int startIndex;

    /** Ostatni dopuszczalny indeks (inkluzywny). */
    public final int endIndex;

    /**
     * Liczba elementów tablicy (= endIndex - startIndex + 1).
     * Odpowiada parametrowi N w LLVM: alloca [N x T].
     */
    public final int size;

    /**
     * Tworzy tablicę 0-based (np. int[5] arr → indeksy 0..4).
     *
     * @param elementType typ elementów
     * @param size        liczba elementów (N ≥ 1)
     */
    public ArrayInfo(VarType elementType, int size) {
        this(elementType, 0, size - 1);
    }

    /**
     * Tworzy tablicę z dowolnym zakresem indeksów (np. int[1..5] arr → indeksy 1..5).
     *
     * @param elementType typ elementów
     * @param startIndex  pierwszy indeks (a w a..b)
     * @param endIndex    ostatni indeks  (b w a..b), musi być ≥ startIndex
     */
    public ArrayInfo(VarType elementType, int startIndex, int endIndex) {
        this.elementType = elementType;
        this.startIndex  = startIndex;
        this.endIndex    = endIndex;
        this.size        = endIndex - startIndex + 1;
    }

    @Override
    public String toString() {
        return "ArrayInfo(" + elementType + "[" + startIndex + ".." + endIndex + "], size=" + size + ")";
    }
}
