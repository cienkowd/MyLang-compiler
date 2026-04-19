/**
 * Generator kodu LLVM IR dla kompilatora MyLang.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  ARCHITEKTURA GENERATORA
 * ═══════════════════════════════════════════════════════════════════
 *
 * Generator buduje program LLVM IR jako dwa ciągi tekstowe:
 *
 *  header_text – deklaracje globalne (stałe format strings, literały string)
 *                emitowane PRZED definicją @main
 *
 *  main_text   – ciało funkcji @main: instrukcje alloca, load, store,
 *                operacje arytmetyczne, wywołania printf/scanf, itp.
 *
 * Ostateczny program składa się z:
 *   declare i32 @printf(...)           ← deklaracje zewnętrznych funkcji C
 *   declare i32 @__isoc99_scanf(...)
 *   declare i8* @strcpy(...)
 *   @fmt_i32_p = constant ...         ← globalne format strings
 *   @strlit_1  = constant ...         ← literały string z kodu użytkownika
 *   define i32 @main() {              ← ciało programu
 *     [main_text]
 *     ret i32 0
 *   }
 *
 * ═══════════════════════════════════════════════════════════════════
 *  NUMERACJA REJESTRÓW (SSA)
 * ═══════════════════════════════════════════════════════════════════
 *
 * LLVM IR używa formy SSA (Static Single Assignment): każdy rejestr
 * jest przypisywany dokładnie raz. Rejestry są ponumerowane sekwencyjnie:
 * %1, %2, %3, ...
 *
 * WAŻNE: wewnątrz funkcji @main LLVM wymaga, żeby każde wyrażenie
 * wytwarzające wartość (load, add, call zwracające nie-void, itp.)
 * dostało numer w kolejności. Nie można pomijać numerów ani używać
 * nazw symbolicznych w środkowej sekwencji – stąd pole `reg`.
 *
 * Wyjątek: instrukcje void (store, br) numerów nie dostają.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  ETYKIETY BLOKÓW PODSTAWOWYCH
 * ═══════════════════════════════════════════════════════════════════
 *
 * Short-circuit (AND/OR) i inne skoki wymagają bloków podstawowych
 * z unikalnymi etykietami. Używamy labelCount jako globalnego licznika.
 * Pole currentBlock śledzi aktualny blok dla instrukcji phi.
 */
public class LLVMGenerator {

    // ─── Stan generatora ──────────────────────────────────────────────────────

    /** Globalne deklaracje: format strings, literały stringów. */
    static String header_text = "";

    /** Ciało funkcji @main: wszystkie instrukcje programu. */
    static String main_text   = "";

    /** Licznik rejestrów wirtualnych LLVM (%1, %2, ...). */
    static int reg          = 1;

    /** Licznik etykiet bloków podstawowych (and_eval_0, or_end_1, ...). */
    static int labelCount   = 0;

    /** Licznik buforów tymczasowych dla read bool/bool-array. */
    static int readBufCount = 0;

    /** Licznik globalnych literałów stringowych (@strlit_1, @strlit_2, ...). */
    static int strLitCount  = 0;

    /**
     * Nazwa aktualnie aktywnego bloku podstawowego.
     * Potrzebna do generowania instrukcji phi (musi wiedzieć, z jakiego bloku
     * pochodzi każda gałąź wartości).
     */
    static String currentBlock = "entry";

    // ─── Pomocnicze ───────────────────────────────────────────────────────────

    /** Zwraca kolejny unikalny rejestr LLVM (np. "%5"). */
    static String nextReg() {
        return "%" + reg++;
    }

    /**
     * Tworzy unikalną etykietę bloku z podanym prefiksem.
     * Używane w LLVMActions do generowania nazw bloków short-circuit.
     * Przykład: newLabel("and_eval") → "and_eval_3"
     */
    static String newLabel(String prefix) {
        return prefix + "_" + (labelCount++);
    }

    // ─── Deklaracje zmiennych ─────────────────────────────────────────────────

    /**
     * Rezerwuje stos dla zmiennej skalarnej (int, real, float32, float64, bool).
     *
     * Generuje: %id = alloca T
     * Gdzie T = elemType.llvm() (np. i32, double, float, i1)
     */
    static void declare(String id, VarType t) {
        main_text += "  %" + id + " = alloca " + t.llvm() + "\n";
    }

    /**
     * Rezerwuje stos dla zmiennej stringowej.
     *
     * String w MyLang to wskaźnik i8* wskazujący na własny bufor 256 bajtów.
     * Struktura na stosie:
     *   %id     = alloca i8*         ← wskaźnik (będzie wskazywał na bufor)
     *   %id_buf = alloca [256 x i8]  ← bufor na znaki (max 255 + '\0')
     *
     * Po alokacji wskaźnik jest od razu ustawiany na początek bufora,
     * więc %id można używać jak zmiennej stringowej:
     *   %ptr = getelementptr [256 x i8], [256 x i8]* %id_buf, i32 0, i32 0
     *   store i8* %ptr, i8** %id
     */
    static void declareString(String id) {
        main_text += "  %" + id + " = alloca i8*\n";
        main_text += "  %" + id + "_buf = alloca [256 x i8]\n";
        String ptr = nextReg();
        main_text += "  " + ptr + " = getelementptr inbounds [256 x i8], "
                   + "[256 x i8]* %" + id + "_buf, i32 0, i32 0\n";
        main_text += "  store i8* " + ptr + ", i8** %" + id + "\n";
    }

    /**
     * Rezerwuje stos dla tablicy.
     *
     * Generuje: %id = alloca [size x T]
     * Elementy są dostępne przez getelementptr (→ arrayGEP).
     */
    static void declareArray(String id, VarType elemType, int size) {
        main_text += "  %" + id + " = alloca [" + size + " x " + elemType.llvm() + "]\n";
    }

    // ─── Store / load ─────────────────────────────────────────────────────────

    /**
     * Zapisuje wartość do zmiennej skalarnej.
     * Generuje: store T val, T* %id
     */
    static void assign(String id, String val, VarType t) {
        main_text += "  store " + t.llvm() + " " + val + ", " + t.llvm() + "* %" + id + "\n";
    }

    /**
     * Ładuje wartość zmiennej skalarnej do nowego rejestru.
     * Generuje: %N = load T, T* %id
     *
     * @return nazwa rejestru z załadowaną wartością
     */
    static String load(String id, VarType t) {
        String r = nextReg();
        main_text += "  " + r + " = load " + t.llvm() + ", " + t.llvm() + "* %" + id + "\n";
        return r;
    }

    /**
     * Ładuje wskaźnik i8* ze zmiennej stringowej.
     * Generuje: %N = load i8*, i8** %id
     *
     * @return rejestr z adresem początku stringa
     */
    static String loadString(String id) {
        String r = nextReg();
        main_text += "  " + r + " = load i8*, i8** %" + id + "\n";
        return r;
    }

    /**
     * Oblicza wskaźnik do elementu tablicy (getelementptr).
     *
     * Generuje:
     *   %N = getelementptr inbounds [size x T], [size x T]* %id, i32 0, i32 idxReg
     *
     * Pierwsze i32 0 wybiera tablicę (indeks do wskaźnika zewnętrznego),
     * drugie i32 idxReg wybiera element wewnątrz tablicy.
     *
     * UWAGA: idxReg musi być już po korekcie o startIndex (→ adjustIndex w LLVMActions).
     *
     * @param idxReg  rejestr lub literał int z (skorygowanym) indeksem
     * @return        rejestr z wskaźnikiem do elementu (T*)
     */
    static String arrayGEP(String id, String idxReg, VarType elemType, int size) {
        String r = nextReg();
        String T = elemType.llvm();
        main_text += "  " + r + " = getelementptr inbounds [" + size + " x " + T + "], ["
                   + size + " x " + T + "]* %" + id + ", i32 0, i32 " + idxReg + "\n";
        return r;
    }

    /**
     * Ładuje wartość przez wskaźnik (używane dla elementów tablic).
     * Generuje: %N = load T, T* ptr
     */
    static String loadFromPtr(String ptr, VarType elemType) {
        String r = nextReg();
        main_text += "  " + r + " = load " + elemType.llvm() + ", "
                   + elemType.llvm() + "* " + ptr + "\n";
        return r;
    }

    /**
     * Zapisuje wartość przez wskaźnik (używane dla elementów tablic).
     * Generuje: store T val, T* ptr
     */
    static void storeToPtr(String ptr, String val, VarType elemType) {
        main_text += "  store " + elemType.llvm() + " " + val + ", "
                   + elemType.llvm() + "* " + ptr + "\n";
    }

    // ─── Arytmetyka ───────────────────────────────────────────────────────────
    //
    // Dla int: add/sub/mul/sdiv  (instrukcje całkowitoliczbowe)
    // Dla float: fadd/fsub/fmul/fdiv  (instrukcje zmiennoprzecinkowe, IEEE754)
    // Negacja int: sub i32 0, v  (LLVM nie ma neg dla całkowitoliczbowych)
    // Negacja float: fneg T v    (dostępne od LLVM 8)

    static String add(String v1, String v2, VarType t) {
        String r = nextReg();
        if (t == VarType.INT)
            main_text += "  " + r + " = add i32 " + v1 + ", " + v2 + "\n";
        else
            main_text += "  " + r + " = fadd " + t.llvm() + " " + v1 + ", " + v2 + "\n";
        return r;
    }

    static String sub(String v1, String v2, VarType t) {
        String r = nextReg();
        if (t == VarType.INT)
            main_text += "  " + r + " = sub i32 " + v1 + ", " + v2 + "\n";
        else
            main_text += "  " + r + " = fsub " + t.llvm() + " " + v1 + ", " + v2 + "\n";
        return r;
    }

    static String mul(String v1, String v2, VarType t) {
        String r = nextReg();
        if (t == VarType.INT)
            main_text += "  " + r + " = mul i32 " + v1 + ", " + v2 + "\n";
        else
            main_text += "  " + r + " = fmul " + t.llvm() + " " + v1 + ", " + v2 + "\n";
        return r;
    }

    static String div(String v1, String v2, VarType t) {
        String r = nextReg();
        if (t == VarType.INT)
            main_text += "  " + r + " = sdiv i32 " + v1 + ", " + v2 + "\n"; // signed division
        else
            main_text += "  " + r + " = fdiv " + t.llvm() + " " + v1 + ", " + v2 + "\n";
        return r;
    }

    static String neg(String v, VarType t) {
        String r = nextReg();
        if (t == VarType.INT)
            main_text += "  " + r + " = sub i32 0, " + v + "\n"; // brak neg dla i32
        else
            main_text += "  " + r + " = fneg " + t.llvm() + " " + v + "\n";
        return r;
    }

    // ─── Operacje logiczne na i1 ──────────────────────────────────────────────
    //
    // Używane dla XOR (brak short-circuit) i NOT.
    // AND/OR ze short-circuit generowane przez LLVMActions (condBr + phi).

    static String and_i1(String v1, String v2) {
        String r = nextReg();
        main_text += "  " + r + " = and i1 " + v1 + ", " + v2 + "\n";
        return r;
    }

    static String or_i1(String v1, String v2) {
        String r = nextReg();
        main_text += "  " + r + " = or i1 " + v1 + ", " + v2 + "\n";
        return r;
    }

    /** XOR: używane dla operatora XOR (bez short-circuit). */
    static String xor_i1(String v1, String v2) {
        String r = nextReg();
        main_text += "  " + r + " = xor i1 " + v1 + ", " + v2 + "\n";
        return r;
    }

    /**
     * NOT: implementowane jako XOR z true.
     * Generuje: %N = xor i1 v, true
     */
    static String not_i1(String v) {
        String r = nextReg();
        main_text += "  " + r + " = xor i1 " + v + ", true\n";
        return r;
    }

    // ─── Porównania ───────────────────────────────────────────────────────────

    /**
     * Porównanie liczb całkowitych.
     * Generuje: %N = icmp op i32 v1, v2
     *
     * op to jeden z: eq, ne, slt, sgt, sle, sge
     * (s-prefiks = signed, bo int jest signed w MyLang)
     *
     * @return rejestr i1 (bool) z wynikiem porównania
     */
    static String icmp(String op, String v1, String v2) {
        String r = nextReg();
        main_text += "  " + r + " = icmp " + op + " i32 " + v1 + ", " + v2 + "\n";
        return r;
    }

    /**
     * Porównanie liczb zmiennoprzecinkowych (ordered – zwraca false gdy NaN).
     * Generuje: %N = fcmp op T v1, v2
     *
     * op to jeden z: oeq, one, olt, ogt, ole, oge
     * (o-prefiks = ordered, wyklucza NaN)
     *
     * @return rejestr i1 (bool) z wynikiem porównania
     */
    static String fcmp(String op, String v1, String v2, VarType t) {
        String r = nextReg();
        main_text += "  " + r + " = fcmp " + op + " " + t.llvm() + " " + v1 + ", " + v2 + "\n";
        return r;
    }

    // ─── Short-circuit: sterowanie przepływem ─────────────────────────────────
    //
    // Short-circuit AND/OR wymaga warunkowych skoków i węzłów phi.
    // Schemat dla AND (b1 AND b2):
    //
    //   [bieżący blok]
    //     %left = ...
    //     br i1 %left, label %and_eval, label %and_false
    //
    //   and_eval:                     ← oblicz prawą stronę tylko jeśli lewa=true
    //     %right = ...
    //     br label %and_end
    //
    //   and_false:                    ← skrót: lewa=false, wynik=false
    //     br label %and_end
    //
    //   and_end:
    //     %result = phi i1 [%right, %and_eval], [false, %and_false]

    /** Warunkowy skok: if cond goto trueLabel else goto falseLabel */
    static void condBr(String cond, String trueLabel, String falseLabel) {
        main_text += "  br i1 " + cond + ", label %" + trueLabel
                   + ", label %" + falseLabel + "\n";
    }

    /** Bezwarunkowy skok do etykiety. */
    static void br(String label) {
        main_text += "  br label %" + label + "\n";
    }

    /**
     * Emituje etykietę bloku podstawowego i aktualizuje currentBlock.
     * currentBlock jest używany przez phi_i1 do poprawnej budowy węzła phi.
     */
    static void emitLabel(String label) {
        main_text += label + ":\n";
        currentBlock = label;
    }

    /**
     * Węzeł phi dla i1 – scala wartości z dwóch różnych ścieżek przepływu.
     *
     * Generuje:
     *   %N = phi i1 [v1, %l1], [v2, %l2]
     *
     * Semantyka: jeśli wykonanie przyszło z bloku l1, wynik = v1;
     *            jeśli z bloku l2, wynik = v2.
     *
     * @return rejestr i1 z wybraną wartością
     */
    static String phi_i1(String v1, String l1, String v2, String l2) {
        String r = nextReg();
        main_text += "  " + r + " = phi i1 "
                   + "[" + v1 + ", %" + l1 + "], "
                   + "[" + v2 + ", %" + l2 + "]\n";
        return r;
    }

    // ─── Konwersje typów ──────────────────────────────────────────────────────
    //
    // LLVM wymaga jawnych instrukcji konwersji przy zmianie typu.
    // Hierarchia szerokości (promocja automatyczna w obu kierunkach):
    //   bool (i1) ─── int (i32) ─── float32 (float) ─── float64/real (double)
    //
    // Instrukcje LLVM:
    //   sitofp  : signed int → float/double
    //   fptosi  : float/double → signed int (obcięcie)
    //   fpext   : float → double (poszerzenie, besstratne)
    //   fptrunc : double → float (zawężenie, możliwa utrata precyzji)
    //   zext    : i1 → i32 (zero-extension: false=0, true=1)
    //   icmp ne : i32 → i1 (0=false, pozostałe=true)
    //   fcmp une: float → i1 (0.0=false, pozostałe=true)

    /** int (i32) → double */
    static String intToDouble(String v) {
        String r = nextReg();
        main_text += "  " + r + " = sitofp i32 " + v + " to double\n";
        return r;
    }

    /** int (i32) → float (float32) */
    static String intToFloat(String v) {
        String r = nextReg();
        main_text += "  " + r + " = sitofp i32 " + v + " to float\n";
        return r;
    }

    /** double → int (i32), obcięcie w kierunku zera (truncation) */
    static String doubleToInt(String v) {
        String r = nextReg();
        main_text += "  " + r + " = fptosi double " + v + " to i32\n";
        return r;
    }

    /** float → int (i32), obcięcie w kierunku zera */
    static String floatToInt(String v) {
        String r = nextReg();
        main_text += "  " + r + " = fptosi float " + v + " to i32\n";
        return r;
    }

    /** float → double (fpext – besstratne poszerzenie) */
    static String floatToDouble(String v) {
        String r = nextReg();
        main_text += "  " + r + " = fpext float " + v + " to double\n";
        return r;
    }

    /** double → float (fptrunc – zawężenie, możliwa utrata precyzji) */
    static String doubleToFloat(String v) {
        String r = nextReg();
        main_text += "  " + r + " = fptrunc double " + v + " to float\n";
        return r;
    }

    /** int (i32) → bool (i1): 0 → false, reszta → true */
    static String intToBool(String v) {
        String r = nextReg();
        main_text += "  " + r + " = icmp ne i32 " + v + ", 0\n";
        return r;
    }

    /** float/double → bool (i1): 0.0 → false, reszta → true */
    static String floatToBool(String v, VarType t) {
        String r = nextReg();
        main_text += "  " + r + " = fcmp une " + t.llvm() + " " + v + ", 0.0\n";
        return r;
    }

    /** bool (i1) → int (i32): false=0, true=1, przez zero-extension */
    static String boolToInt(String v) {
        String r = nextReg();
        main_text += "  " + r + " = zext i1 " + v + " to i32\n";
        return r;
    }

    /** bool (i1) → float lub double: najpierw zext do i32, potem sitofp */
    static String boolToFloat(String v, VarType target) {
        String asInt = boolToInt(v);
        if (target == VarType.FLOAT32) return intToFloat(asInt);
        else                           return intToDouble(asInt);
    }

    // ─── Wejście / wyjście ────────────────────────────────────────────────────
    //
    // Wywołania printf/scanf generują wynik int (liczba znaków / 1 jeśli OK),
    // który MUSI być przypisany do rejestru (LLVM wymaga sekwencji numerów).
    //
    // Uwaga: printf jest funkcją wariadyczną, więc wymaga pełnego prototypu:
    //   call i32 (i8*, ...) @printf(...)
    //
    // Uwaga: float musi być poszerzony do double przed przekazaniem do printf,
    // bo C ABI promuje float → double dla argumentów wariadycznych.

    /**
     * Wypisuje liczbę całkowitą (format "%d\n").
     * Generuje: %N = call i32 (i8*, ...) @printf(i8* @fmt_i32_p, i32 val)
     */
    static void printf_i32(String val) {
        String r = nextReg();
        main_text += "  " + r + " = call i32 (i8*, ...) @printf("
                   + "i8* getelementptr inbounds ([4 x i8], [4 x i8]* @fmt_i32_p, i32 0, i32 0), "
                   + "i32 " + val + ")\n";
    }

    /**
     * Wypisuje liczbę double (format "%f\n").
     * Generuje: %N = call i32 (i8*, ...) @printf(i8* @fmt_f64_p, double val)
     */
    static void printf_double(String val) {
        String r = nextReg();
        main_text += "  " + r + " = call i32 (i8*, ...) @printf("
                   + "i8* getelementptr inbounds ([4 x i8], [4 x i8]* @fmt_f64_p, i32 0, i32 0), "
                   + "double " + val + ")\n";
    }

    /**
     * Wypisuje liczbę float.
     * C ABI wymaga promocji float → double dla wariadycznych argumentów,
     * dlatego najpierw fpext, potem printf z double.
     */
    static void printf_float(String val) {
        String ext = floatToDouble(val); // fpext float → double
        String r = nextReg();
        main_text += "  " + r + " = call i32 (i8*, ...) @printf("
                   + "i8* getelementptr inbounds ([4 x i8], [4 x i8]* @fmt_f64_p, i32 0, i32 0), "
                   + "double " + ext + ")\n";
    }

    /**
     * Wypisuje wartość logiczną jako 0 lub 1.
     * Strategia: zext i1 → i32, potem printf "%d\n".
     */
    static void printf_bool(String val) {
        String asInt = boolToInt(val);
        printf_i32(asInt);
    }

    /**
     * Wypisuje ciąg znaków (format "%s\n").
     * Generuje: %N = call i32 (i8*, ...) @printf(i8* @fmt_str_p, i8* ptr)
     */
    static void printf_string(String ptr) {
        String r = nextReg();
        main_text += "  " + r + " = call i32 (i8*, ...) @printf("
                   + "i8* getelementptr inbounds ([4 x i8], [4 x i8]* @fmt_str_p, i32 0, i32 0), "
                   + "i8* " + ptr + ")\n";
    }

    /**
     * Wczytuje int z stdin do zmiennej (format "%d").
     * Generuje: %N = call i32 (i8*, ...) @__isoc99_scanf(i8* @fmt_i32_r, i32* %id)
     */
    static void scanf_i32(String id) {
        String r = nextReg();
        main_text += "  " + r + " = call i32 (i8*, ...) @__isoc99_scanf("
                   + "i8* getelementptr inbounds ([3 x i8], [3 x i8]* @fmt_i32_r, i32 0, i32 0), "
                   + "i32* %" + id + ")\n";
    }

    /** Wczytuje double z stdin (format "%lf" – wymagane dla scanf). */
    static void scanf_double(String id) {
        String r = nextReg();
        main_text += "  " + r + " = call i32 (i8*, ...) @__isoc99_scanf("
                   + "i8* getelementptr inbounds ([4 x i8], [4 x i8]* @fmt_f64_r, i32 0, i32 0), "
                   + "double* %" + id + ")\n";
    }

    /** Wczytuje float z stdin (format "%f"). */
    static void scanf_float(String id) {
        String r = nextReg();
        main_text += "  " + r + " = call i32 (i8*, ...) @__isoc99_scanf("
                   + "i8* getelementptr inbounds ([3 x i8], [3 x i8]* @fmt_f32_r, i32 0, i32 0), "
                   + "float* %" + id + ")\n";
    }

    /**
     * Wczytuje bool z stdin.
     * Strategia: wczytaj int do bufora tymczasowego, skonwertuj do i1.
     *   %tmp_bool_N = alloca i32
     *   %r1 = scanf("%d", &tmp_bool_N)
     *   %loaded = load i32, i32* %tmp_bool_N
     *   %result = icmp ne i32 %loaded, 0
     *   store i1 %result, i1* %id
     */
    static void scanf_bool(String id) {
        String tmp = "tmp_bool_" + readBufCount++;
        main_text += "  %" + tmp + " = alloca i32\n";
        String r1 = nextReg();
        main_text += "  " + r1 + " = call i32 (i8*, ...) @__isoc99_scanf("
                   + "i8* getelementptr inbounds ([3 x i8], [3 x i8]* @fmt_i32_r, i32 0, i32 0), "
                   + "i32* %" + tmp + ")\n";
        String loaded = nextReg();
        main_text += "  " + loaded + " = load i32, i32* %" + tmp + "\n";
        String asBool = intToBool(loaded);
        main_text += "  store i1 " + asBool + ", i1* %" + id + "\n";
    }

    /**
     * Wczytuje string z stdin (format "%255s" – max 255 znaków, chroni przed overflow).
     * Ładuje adres bufora ze wskaźnika, wczytuje wprost do bufora.
     */
    static void scanf_string(String id) {
        String ptr = nextReg();
        main_text += "  " + ptr + " = load i8*, i8** %" + id + "\n";
        String r = nextReg();
        main_text += "  " + r + " = call i32 (i8*, ...) @__isoc99_scanf("
                   + "i8* getelementptr inbounds ([6 x i8], [6 x i8]* @fmt_str_r, i32 0, i32 0), "
                   + "i8* " + ptr + ")\n";
    }

    /**
     * Wczytuje wartość bezpośrednio do wskaźnika elementu tablicy.
     * Używane przez read arr[i] – wskaźnik pochodzi z arrayGEP.
     */
    static void scanf_into_ptr(String ptr, VarType elemType) {
        String r = nextReg();
        switch (elemType) {
            case INT:
                main_text += "  " + r + " = call i32 (i8*, ...) @__isoc99_scanf("
                           + "i8* getelementptr inbounds ([3 x i8], [3 x i8]* @fmt_i32_r, i32 0, i32 0), "
                           + "i32* " + ptr + ")\n";
                break;
            case REAL:
            case FLOAT64:
                main_text += "  " + r + " = call i32 (i8*, ...) @__isoc99_scanf("
                           + "i8* getelementptr inbounds ([4 x i8], [4 x i8]* @fmt_f64_r, i32 0, i32 0), "
                           + "double* " + ptr + ")\n";
                break;
            case FLOAT32:
                main_text += "  " + r + " = call i32 (i8*, ...) @__isoc99_scanf("
                           + "i8* getelementptr inbounds ([3 x i8], [3 x i8]* @fmt_f32_r, i32 0, i32 0), "
                           + "float* " + ptr + ")\n";
                break;
            case BOOL: {
                // Pośrednie wczytanie przez bufor int, jak w scanf_bool
                String tmp = "tmp_bool_arr_" + readBufCount++;
                main_text += "  %" + tmp + " = alloca i32\n";
                String r1 = nextReg();
                main_text += "  " + r1 + " = call i32 (i8*, ...) @__isoc99_scanf("
                           + "i8* getelementptr inbounds ([3 x i8], [3 x i8]* @fmt_i32_r, i32 0, i32 0), "
                           + "i32* %" + tmp + ")\n";
                String loaded = nextReg();
                main_text += "  " + loaded + " = load i32, i32* %" + tmp + "\n";
                String asBool = intToBool(loaded);
                main_text += "  store i1 " + asBool + ", i1* " + ptr + "\n";
                break;
            }
            default:
                break;
        }
    }

    // ─── Tablice – operacje zbiorcze ──────────────────────────────────────────

    /**
     * Wypisuje wszystkie elementy tablicy (compile-time unrolled loop).
     *
     * Zamiast generować pętlę (co wymagałoby bloków i phi), rozwijamy ją
     * statycznie w czasie kompilacji: dla każdego indeksu i=0..size-1
     * generujemy GEP + load + printf. Efekt jest identyczny z pętlą,
     * ale IR jest prostszy (brak bloków podstawowych, phi, branch).
     *
     * Przykład dla int[3] arr:
     *   %1 = getelementptr [3 x i32], [3 x i32]* %arr, i32 0, i32 0
     *   %2 = load i32, i32* %1
     *   %3 = call i32 @printf(@fmt_i32_p, i32 %2)
     *   %4 = getelementptr [3 x i32], [3 x i32]* %arr, i32 0, i32 1
     *   ...
     */
    static void printArrayAll(String id, VarType elemType, int size) {
        String T = elemType.llvm();
        for (int i = 0; i < size; i++) {
            // GEP do elementu o indeksie i (już 0-based w LLVM)
            String gepReg = nextReg();
            main_text += "  " + gepReg + " = getelementptr inbounds [" + size + " x " + T + "], ["
                       + size + " x " + T + "]* %" + id + ", i32 0, i32 " + i + "\n";
            // Załaduj wartość
            String valReg = nextReg();
            main_text += "  " + valReg + " = load " + T + ", " + T + "* " + gepReg + "\n";
            // Wypisz
            switch (elemType) {
                case INT:             printf_i32(valReg);    break;
                case REAL:
                case FLOAT64:         printf_double(valReg); break;
                case FLOAT32:         printf_float(valReg);  break;
                case BOOL:            printf_bool(valReg);   break;
                default:              break; // string w tablicach nieobsługiwany
            }
        }
    }

    // ─── Literały stringowe ───────────────────────────────────────────────────

    /**
     * Tworzy globalną stałą dla literału stringowego i zwraca rejestr GEP.
     *
     * Każde wystąpienie literału string w kodzie źródłowym dostaje
     * własną globalną stałą w sekcji header:
     *   @strlit_N = constant [len x i8] c"tekst\00"
     *
     * Następnie generuje GEP do początku stałej:
     *   %M = getelementptr inbounds [len x i8], [len x i8]* @strlit_N, i32 0, i32 0
     *
     * @param content zawartość stringa (bez cudzysłowów)
     * @return        rejestr i8* wskazujący na stałą
     */
    static String stringLiteralPtr(String content) {
        int id = ++strLitCount;
        String gname = "@strlit_" + id;
        int len = content.length() + 1; // +1 na terminator \00
        // Escape znaków specjalnych wewnątrz stałej LLVM
        String escaped = content.replace("\\", "\\5C").replace("\"", "\\22");
        header_text += gname + " = constant [" + len + " x i8] c\""
                     + escaped + "\\00\"\n";
        String r = nextReg();
        main_text += "  " + r + " = getelementptr inbounds [" + len + " x i8], ["
                   + len + " x i8]* " + gname + ", i32 0, i32 0\n";
        return r;
    }

    // ─── Generowanie wyjścia ──────────────────────────────────────────────────

    /**
     * Składa kompletny moduł LLVM IR ze wszystkich zgromadzonych części.
     *
     * Struktura wyjścia:
     *   1. Deklaracje funkcji zewnętrznych (printf, scanf, strcpy)
     *   2. Globalne format strings (fmt_i32_p, fmt_f64_r, itp.)
     *   3. Literały stringowe z kodu użytkownika (header_text)
     *   4. Definicja @main z ciałem programu (main_text)
     *
     * @return kompletny tekst LLVM IR gotowy do wykonania przez lli lub clang
     */
    static String generate() {
        StringBuilder sb = new StringBuilder();

        // Deklaracje zewnętrznych funkcji C
        sb.append("; ── Zewnętrzne funkcje C ─────────────────────────────────────────────\n");
        sb.append("declare i32 @printf(i8*, ...)\n");
        sb.append("declare i32 @__isoc99_scanf(i8*, ...)\n");
        sb.append("declare i8* @strcpy(i8*, i8*)\n");
        sb.append("\n");

        // Format strings dla printf (%d\n, %f\n, %s\n) i scanf (%d, %lf, %f, %255s)
        sb.append("; ── Format strings printf/scanf ─────────────────────────────────────\n");
        sb.append("@fmt_i32_p = constant [4 x i8] c\"%d\\0A\\00\"\n");   // "%d\n\0"
        sb.append("@fmt_i32_r = constant [3 x i8] c\"%d\\00\"\n");         // "%d\0"
        sb.append("@fmt_f64_p = constant [4 x i8] c\"%f\\0A\\00\"\n");   // "%f\n\0"
        sb.append("@fmt_f64_r = constant [4 x i8] c\"%lf\\00\"\n");        // "%lf\0" (scanf double)
        sb.append("@fmt_f32_r = constant [3 x i8] c\"%f\\00\"\n");         // "%f\0"  (scanf float)
        sb.append("@fmt_str_p = constant [4 x i8] c\"%s\\0A\\00\"\n");   // "%s\n\0"
        sb.append("@fmt_str_r = constant [6 x i8] c\"%255s\\00\"\n");      // "%255s\0" (max 255 znaków)
        sb.append("\n");

        // Literały stringowe z kodu użytkownika
        if (!header_text.isEmpty()) {
            sb.append("; ── Literały stringowe ───────────────────────────────────────────────\n");
            sb.append(header_text);
            sb.append("\n");
        }

        // Ciało programu
        sb.append("; ── Ciało programu ───────────────────────────────────────────────────\n");
        sb.append("define i32 @main() nounwind {\n");
        sb.append(main_text);
        sb.append("  ret i32 0\n");
        sb.append("}\n");

        return sb.toString();
    }
}
