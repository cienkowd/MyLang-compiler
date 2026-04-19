import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.TerminalNode;
import java.util.HashMap;
import java.util.Map;

/**
 * Visitor semantyczny kompilatora MyLang.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  WZORZEC VISITOR
 * ═══════════════════════════════════════════════════════════════════
 *
 * ANTLR4 generuje interfejs MyLangVisitor<T> z metodą visit*(ctx) dla
 * każdej nazwanej alternatywy gramatyki (np. #addOp → visitAddOp).
 * LLVMActions implementuje go przez rozszerzenie MyLangBaseVisitor<Value>.
 *
 * Dla każdego węzła AST visitor:
 *   1. Rekurencyjnie odwiedza dzieci (visit(ctx.child()))
 *   2. Na podstawie typów dzieci decyduje o konwersjach
 *   3. Wywołuje odpowiednie metody LLVMGenerator
 *   4. Zwraca Value(rejestr, typ) reprezentujące wynik
 *
 * Instrukcje (stat) zwracają null (nie produkują wartości).
 * Wyrażenia (expr) zwracają Value z rejestrem i typem wyniku.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  SYSTEM TYPÓW I KOERCJA
 * ═══════════════════════════════════════════════════════════════════
 *
 * Hierarchia szerokości typów liczbowych (od najwęższego):
 *   int (i32)  <  float32 (float)  <  real/float64 (double)
 *
 * Przy operacjach binarnych oba operandy są podnoszone do szerszego
 * wspólnego typu (metoda unifyNumeric).
 *
 * Przy deklaracji/przypisaniu: niejawna koercja przez coerce()
 *   - int → real/float32/float64 (bezstratna)
 *   - float32 ↔ real/float64 (poszerzenie lub zawężenie)
 *
 * Jawne rzutowanie przez cast(): dowolne konwersje typów liczbowych,
 * w tym float → int (obcięcie), int → bool, bool → int, itp.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  SHORT-CIRCUIT EVALUATION (AND / OR)
 * ═══════════════════════════════════════════════════════════════════
 *
 * AND z short-circuit (b1 AND b2):
 *   Jeśli b1 = false, wynik jest false i b2 nie jest obliczane.
 *
 *   [bieżący blok]          br i1 %b1, %and_eval_N, %and_false_N
 *   and_eval_N:             %b2 = ...  (oblicz b2)
 *                           br %and_end_N
 *   and_false_N:            br %and_end_N
 *   and_end_N:              %r = phi i1 [%b2, %and_eval_N], [false, %and_false_N]
 *
 * OR z short-circuit (b1 OR b2):
 *   Jeśli b1 = true, wynik jest true i b2 nie jest obliczane.
 *
 *   [bieżący blok]          br i1 %b1, %or_true_N, %or_eval_N
 *   or_eval_N:              %b2 = ...  (oblicz b2)
 *                           br %or_end_N
 *   or_true_N:              br %or_end_N
 *   or_end_N:               %r = phi i1 [%b2, %or_eval_N], [true, %or_true_N]
 */
public class LLVMActions extends MyLangBaseVisitor<Value> {

    // ─── Tablice symboli ──────────────────────────────────────────────────────

    /** Zmienne skalarne: nazwa → typ. Wypełniana przy deklaracji. */
    private final Map<String, VarType>    variables = new HashMap<>();

    /** Tablice: nazwa → metadane (typ elementów, zakres indeksów). */
    private final Map<String, ArrayInfo>  arrays    = new HashMap<>();

    /** Licznik błędów semantycznych. Gdy > 0, IR nie jest drukowany. */
    private int errors = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    //  PROGRAM
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Korzeń drzewa – odwiedza wszystkie instrukcje, a na końcu
     * drukuje wygenerowany IR (jeśli nie było błędów semantycznych).
     */
    @Override
    public Value visitProg(MyLangParser.ProgContext ctx) {
        visitChildren(ctx);
        if (errors == 0) {
            System.out.print(LLVMGenerator.generate());
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  DEKLARACJE ZMIENNYCH SKALARNYCH
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Deklaracja zmiennej skalarnej z opcjonalną inicjalizacją.
     *
     * Składnia: typeName ID ('=' expr)?
     * Przykłady:
     *   int x          → alloca i32; (bez inicjalizacji)
     *   int y = 5      → alloca i32; store i32 5, i32* %y
     *   real pi = 3.14 → alloca double; store double 3.14, double* %pi
     *   string s = "hi"→ alloca i8*; alloca [256 x i8]; ... strcpy
     *
     * Błędy semantyczne:
     *   - podwójna deklaracja zmiennej
     *   - niezgodność typów przy inicjalizacji
     */
    @Override
    public Value visitVarDecl(MyLangParser.VarDeclContext ctx) {
        String id = ctx.ID().getText();
        VarType t = resolveType(ctx.typeName());

        if (isDeclared(id)) {
            semanticError(ctx, "zmienna '" + id + "' już została zadeklarowana");
            return null;
        }
        variables.put(id, t);

        if (t == VarType.STRING) {
            LLVMGenerator.declareString(id);
        } else {
            LLVMGenerator.declare(id, t);
        }

        if (ctx.expr() != null) {
            Value val = visit(ctx.expr());
            if (val == null) return null;

            if (t == VarType.STRING) {
                if (val.type != VarType.STRING) {
                    semanticError(ctx, "inicjalizacja string wymaga wartości string, podano: " + val.type);
                    return null;
                }
                // Uwaga: loadStringBufPtr MUSI być wywołane przed użyciem val.name
                // w tym samym wyrażeniu – inaczej Java += evaluje RHS z side-effectem
                // który nadpisuje LHS (znany bug z operatorem +=).
                String bufPtr = loadStringBufPtr(id);
                String strcpyReg = LLVMGenerator.nextReg(); // strcpy zwraca i8* – musi dostać numer
                LLVMGenerator.main_text += "  " + strcpyReg
                    + " = call i8* @strcpy(i8* " + bufPtr + ", i8* " + val.name + ")\n";
            } else {
                Value coerced = coerce(val, t, ctx);
                if (coerced == null) return null;
                LLVMGenerator.assign(id, coerced.name, t);
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  DEKLARACJE TABLIC
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Tablica 0-based o stałym rozmiarze.
     *
     * Składnia: typeName '[' INT ']' ID
     * Przykład: int[5] tab → %tab = alloca [5 x i32]
     *           Indeksy 0..4 mapują bezpośrednio na pozycje 0..4 w LLVM.
     */
    @Override
    public Value visitArrayDecl(MyLangParser.ArrayDeclContext ctx) {
        VarType elemType = resolveType(ctx.typeName());
        int size = Integer.parseInt(ctx.INT().getText());
        String id = ctx.ID().getText();

        if (size <= 0) {
            semanticError(ctx, "rozmiar tablicy musi być > 0, podano: " + size);
            return null;
        }
        if (isDeclared(id)) {
            semanticError(ctx, "nazwa '" + id + "' już jest zadeklarowana");
            return null;
        }
        arrays.put(id, new ArrayInfo(elemType, size));
        LLVMGenerator.declareArray(id, elemType, size);
        return null;
    }

    /**
     * Tablica z dowolnym zakresem indeksów.
     *
     * Składnia: typeName '[' INT DOTDOT INT ']' ID
     * Przykład: int[1..3] arr → %arr = alloca [3 x i32]
     *           Indeks 1 mapuje na pozycję 0, indeks 2 na 1, indeks 3 na 2.
     *           Korekta: adjusted = user_index - startIndex (→ adjustIndex).
     */
    @Override
    public Value visitRangedArrayDecl(MyLangParser.RangedArrayDeclContext ctx) {
        VarType elemType = resolveType(ctx.typeName());
        int start = Integer.parseInt(ctx.INT(0).getText());
        int end   = Integer.parseInt(ctx.INT(1).getText());
        String id = ctx.ID().getText();

        if (start > end) {
            semanticError(ctx, "nieprawidłowy zakres tablicy: " + start + ".." + end);
            return null;
        }
        if (isDeclared(id)) {
            semanticError(ctx, "nazwa '" + id + "' już jest zadeklarowana");
            return null;
        }
        ArrayInfo info = new ArrayInfo(elemType, start, end);
        arrays.put(id, info);
        LLVMGenerator.declareArray(id, elemType, info.size);
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PRZYPISANIA
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Przypisanie do zmiennej skalarnej.
     *
     * Składnia: ID '=' expr
     * Przykład: x = x + 1 → load, add, store
     *
     * Niejawna koercja: int → real jest dozwolona (np. real r = 2 + 3).
     */
    @Override
    public Value visitAssign(MyLangParser.AssignContext ctx) {
        String id = ctx.ID().getText();
        if (!variables.containsKey(id)) {
            semanticError(ctx, "niezadeklarowana zmienna '" + id + "'");
            return null;
        }
        VarType t = variables.get(id);
        Value val = visit(ctx.expr());
        if (val == null) return null;

        if (t == VarType.STRING) {
            if (val.type != VarType.STRING) {
                semanticError(ctx, "przypisanie: oczekiwano string, podano " + val.type);
                return null;
            }
            String bufPtr = loadStringBufPtr(id);
            String strcpyReg = LLVMGenerator.nextReg();
            LLVMGenerator.main_text += "  " + strcpyReg
                + " = call i8* @strcpy(i8* " + bufPtr + ", i8* " + val.name + ")\n";
        } else {
            Value coerced = coerce(val, t, ctx);
            if (coerced == null) return null;
            LLVMGenerator.assign(id, coerced.name, t);
        }
        return null;
    }

    /**
     * Przypisanie do elementu tablicy.
     *
     * Składnia: ID '[' expr ']' '=' expr
     * Przykład: arr[2] = 99
     *   1. oblicz indeks → skoryguj o startIndex → GEP → wskaźnik
     *   2. oblicz wartość → koercja do typu elementu
     *   3. store T val, T* gep
     */
    @Override
    public Value visitArrayAssign(MyLangParser.ArrayAssignContext ctx) {
        String id = ctx.ID().getText();
        ArrayInfo info = arrays.get(id);
        if (info == null) {
            semanticError(ctx, "niezadeklarowana tablica '" + id + "'");
            return null;
        }

        Value idxVal = visit(ctx.expr(0));
        if (idxVal == null) return null;
        if (idxVal.type != VarType.INT) {
            semanticError(ctx, "indeks tablicy musi być typu int, podano: " + idxVal.type);
            return null;
        }

        String adjustedIdx = adjustIndex(idxVal.name, info.startIndex);
        String gep = LLVMGenerator.arrayGEP(id, adjustedIdx, info.elementType, info.size);

        Value val = visit(ctx.expr(1));
        if (val == null) return null;
        Value coerced = coerce(val, info.elementType, ctx);
        if (coerced == null) return null;

        LLVMGenerator.storeToPtr(gep, coerced.name, info.elementType);
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  WEJŚCIE / WYJŚCIE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Wypisanie przez ID: print ID
     *
     * Obsługuje dwa przypadki:
     *   - tablica → wypisuje wszystkie elementy (compile-time unroll)
     *   - zmienna skalarna → ładuje i wypisuje jedną wartość
     *
     * ANTLR dopasowuje 'print ID' do tej reguły (przed ogólną 'print expr'),
     * dlatego tu obsługujemy zarówno tablice jak i skalary.
     */
    @Override
    public Value visitPrintIdStat(MyLangParser.PrintIdStatContext ctx) {
        String id = ctx.ID().getText();

        if (arrays.containsKey(id)) {
            ArrayInfo info = arrays.get(id);
            LLVMGenerator.printArrayAll(id, info.elementType, info.size);
        } else if (variables.containsKey(id)) {
            VarType t = variables.get(id);
            Value val = (t == VarType.STRING)
                ? new Value(LLVMGenerator.loadString(id), VarType.STRING)
                : new Value(LLVMGenerator.load(id, t), t);
            emitPrint(val, ctx);
        } else {
            semanticError(ctx, "niezadeklarowana zmienna lub tablica '" + id + "'");
        }
        return null;
    }

    /**
     * Wypisanie wyrażenia złożonego: print expr
     *
     * Używane gdy argument to nie jest samo ID (np. print x + 1, print arr[i]).
     * Odwiedza wyrażenie, uzyskuje Value, wywołuje odpowiedni printf.
     */
    @Override
    public Value visitPrintStat(MyLangParser.PrintStatContext ctx) {
        Value val = visit(ctx.expr());
        if (val == null) return null;
        emitPrint(val, ctx);
        return null;
    }

    /**
     * Odczyt zmiennej skalarnej z stdin: read ID
     *
     * Wywołuje odpowiednią metodę scanf z LLVMGenerator.
     */
    @Override
    public Value visitReadStat(MyLangParser.ReadStatContext ctx) {
        String id = ctx.ID().getText();
        if (!variables.containsKey(id)) {
            semanticError(ctx, "niezadeklarowana zmienna '" + id + "'");
            return null;
        }
        VarType t = variables.get(id);
        switch (t) {
            case INT:     LLVMGenerator.scanf_i32(id);    break;
            case REAL:
            case FLOAT64: LLVMGenerator.scanf_double(id); break;
            case FLOAT32: LLVMGenerator.scanf_float(id);  break;
            case BOOL:    LLVMGenerator.scanf_bool(id);   break;
            case STRING:  LLVMGenerator.scanf_string(id); break;
            default:
                semanticError(ctx, "nie można wczytać wartości typu " + t);
        }
        return null;
    }

    /**
     * Odczyt elementu tablicy z stdin: read ID '[' expr ']'
     *
     * Oblicza GEP elementu i przekazuje wskaźnik do scanf_into_ptr.
     */
    @Override
    public Value visitReadArrayStat(MyLangParser.ReadArrayStatContext ctx) {
        String id = ctx.ID().getText();
        ArrayInfo info = arrays.get(id);
        if (info == null) {
            semanticError(ctx, "niezadeklarowana tablica '" + id + "'");
            return null;
        }
        Value idxVal = visit(ctx.expr());
        if (idxVal == null) return null;
        if (idxVal.type != VarType.INT) {
            semanticError(ctx, "indeks tablicy musi być typu int, podano: " + idxVal.type);
            return null;
        }
        String adjustedIdx = adjustIndex(idxVal.name, info.startIndex);
        String gep = LLVMGenerator.arrayGEP(id, adjustedIdx, info.elementType, info.size);
        LLVMGenerator.scanf_into_ptr(gep, info.elementType);
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  OPERATORY LOGICZNE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * OR z short-circuit: b1 OR b2
     *
     * Schemat bloków (N = unikalny numer z labelCount):
     *
     *   [poprzedni blok]
     *     br i1 %b1, label %or_true_N, label %or_eval_N
     *
     *   or_eval_N:               ← b1 = false, oblicz b2
     *     %b2 = ...
     *     br label %or_end_N
     *
     *   or_true_N:               ← b1 = true, wynik = true (pomiń b2)
     *     br label %or_end_N
     *
     *   or_end_N:
     *     %result = phi i1 [%b2, %or_eval_N], [true, %or_true_N]
     */
    @Override
    public Value visitOrOp(MyLangParser.OrOpContext ctx) {
        Value left = visit(ctx.orExpr());
        if (left == null) return null;
        if (left.type != VarType.BOOL) {
            semanticError(ctx, "OR wymaga operandów bool, lewa strona: " + left.type);
            return null;
        }

        int id = LLVMGenerator.labelCount++;
        String evalLabel = "or_eval_" + id;
        String trueLabel = "or_true_" + id;
        String endLabel  = "or_end_"  + id;

        LLVMGenerator.condBr(left.name, trueLabel, evalLabel);

        LLVMGenerator.emitLabel(evalLabel);
        Value right = visit(ctx.xorExpr());
        if (right == null) return null;
        if (right.type != VarType.BOOL) {
            semanticError(ctx, "OR wymaga operandów bool, prawa strona: " + right.type);
            return null;
        }
        String evalEndBlock = LLVMGenerator.currentBlock; // blok gdzie kończy się obliczenie b2
        LLVMGenerator.br(endLabel);

        LLVMGenerator.emitLabel(trueLabel);
        LLVMGenerator.br(endLabel);

        LLVMGenerator.emitLabel(endLabel);
        String result = LLVMGenerator.phi_i1(right.name, evalEndBlock, "true", trueLabel);
        return new Value(result, VarType.BOOL);
    }

    /**
     * XOR bez short-circuit: b1 XOR b2
     * Zawsze oblicza obie strony, używa instrukcji xor i1.
     */
    @Override
    public Value visitXorOp(MyLangParser.XorOpContext ctx) {
        Value left  = visit(ctx.xorExpr());
        Value right = visit(ctx.andExpr());
        if (left == null || right == null) return null;
        if (left.type != VarType.BOOL || right.type != VarType.BOOL) {
            semanticError(ctx, "XOR wymaga operandów bool");
            return null;
        }
        return new Value(LLVMGenerator.xor_i1(left.name, right.name), VarType.BOOL);
    }

    /**
     * AND z short-circuit: b1 AND b2
     *
     * Schemat bloków (N = unikalny numer z labelCount):
     *
     *   [poprzedni blok]
     *     br i1 %b1, label %and_eval_N, label %and_false_N
     *
     *   and_eval_N:              ← b1 = true, oblicz b2
     *     %b2 = ...
     *     br label %and_end_N
     *
     *   and_false_N:             ← b1 = false, wynik = false (pomiń b2)
     *     br label %and_end_N
     *
     *   and_end_N:
     *     %result = phi i1 [%b2, %and_eval_N], [false, %and_false_N]
     */
    @Override
    public Value visitAndOp(MyLangParser.AndOpContext ctx) {
        Value left = visit(ctx.andExpr());
        if (left == null) return null;
        if (left.type != VarType.BOOL) {
            semanticError(ctx, "AND wymaga operandów bool, lewa strona: " + left.type);
            return null;
        }

        int id = LLVMGenerator.labelCount++;
        String evalLabel  = "and_eval_"  + id;
        String falseLabel = "and_false_" + id;
        String endLabel   = "and_end_"   + id;

        LLVMGenerator.condBr(left.name, evalLabel, falseLabel);

        LLVMGenerator.emitLabel(evalLabel);
        Value right = visit(ctx.notExpr());
        if (right == null) return null;
        if (right.type != VarType.BOOL) {
            semanticError(ctx, "AND wymaga operandów bool, prawa strona: " + right.type);
            return null;
        }
        String evalEndBlock = LLVMGenerator.currentBlock;
        LLVMGenerator.br(endLabel);

        LLVMGenerator.emitLabel(falseLabel);
        LLVMGenerator.br(endLabel);

        LLVMGenerator.emitLabel(endLabel);
        String result = LLVMGenerator.phi_i1(right.name, evalEndBlock, "false", falseLabel);
        return new Value(result, VarType.BOOL);
    }

    /**
     * NOT: negacja logiczna.
     * Implementacja: XOR z true (→ not_i1 w LLVMGenerator).
     */
    @Override
    public Value visitNotOp(MyLangParser.NotOpContext ctx) {
        Value v = visit(ctx.notExpr());
        if (v == null) return null;
        if (v.type != VarType.BOOL) {
            semanticError(ctx, "NOT wymaga operandu bool, podano: " + v.type);
            return null;
        }
        return new Value(LLVMGenerator.not_i1(v.name), VarType.BOOL);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PORÓWNANIA
    // ═══════════════════════════════════════════════════════════════════════════
    //
    // Każdy operator ma osobną metodę (ze względu na ANTLR: #eqOp, #ltOp, itp.),
    // ale wszystkie delegują do prywatnej metody doCmp z odpowiednim operatorem.

    @Override public Value visitEqOp(MyLangParser.EqOpContext ctx) { return doCmp(ctx, ctx.addExpr(0), ctx.addExpr(1), "=="); }
    @Override public Value visitNeOp(MyLangParser.NeOpContext ctx) { return doCmp(ctx, ctx.addExpr(0), ctx.addExpr(1), "!="); }
    @Override public Value visitLtOp(MyLangParser.LtOpContext ctx) { return doCmp(ctx, ctx.addExpr(0), ctx.addExpr(1), "<");  }
    @Override public Value visitGtOp(MyLangParser.GtOpContext ctx) { return doCmp(ctx, ctx.addExpr(0), ctx.addExpr(1), ">");  }
    @Override public Value visitLeOp(MyLangParser.LeOpContext ctx) { return doCmp(ctx, ctx.addExpr(0), ctx.addExpr(1), "<="); }
    @Override public Value visitGeOp(MyLangParser.GeOpContext ctx) { return doCmp(ctx, ctx.addExpr(0), ctx.addExpr(1), ">="); }

    /**
     * Implementacja porównania dla obu operandów.
     *
     * 1. Odwiedź oba operandy
     * 2. Zunifikuj typy (np. int + float32 → float32 + float32)
     * 3. Wybierz icmp (int) lub fcmp (float) z odpowiednim operatorem
     * 4. Zwróć Value(rejestr i1, BOOL)
     */
    private Value doCmp(ParserRuleContext ctx,
                        MyLangParser.AddExprContext lCtx,
                        MyLangParser.AddExprContext rCtx,
                        String op) {
        Value left  = visit(lCtx);
        Value right = visit(rCtx);
        if (left == null || right == null) return null;
        Value[] uni = unifyNumeric(left, right, ctx);
        if (uni == null) return null;
        String result = (uni[0].type == VarType.INT)
            ? LLVMGenerator.icmp(icmpOp(op), uni[0].name, uni[1].name)
            : LLVMGenerator.fcmp(fcmpOp(op), uni[0].name, uni[1].name, uni[0].type);
        return new Value(result, VarType.BOOL);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ARYTMETYKA
    // ═══════════════════════════════════════════════════════════════════════════
    //
    // Wzorzec dla każdej operacji binarnej:
    //   1. visit(lewy operand)  → Value(rejestr, typ)
    //   2. visit(prawy operand) → Value(rejestr, typ)
    //   3. unifyNumeric         → promuj oba do wspólnego szerszego typu
    //   4. LLVMGenerator.op()   → emituj instrukcję LLVM, zwróć rejestr wyniku
    //   5. return Value(wynik, typ)

    @Override
    public Value visitAddOp(MyLangParser.AddOpContext ctx) {
        Value left  = visit(ctx.addExpr());
        Value right = visit(ctx.mulExpr());
        if (left == null || right == null) return null;
        Value[] uni = unifyNumeric(left, right, ctx);
        if (uni == null) return null;
        return new Value(LLVMGenerator.add(uni[0].name, uni[1].name, uni[0].type), uni[0].type);
    }

    @Override
    public Value visitSubOp(MyLangParser.SubOpContext ctx) {
        Value left  = visit(ctx.addExpr());
        Value right = visit(ctx.mulExpr());
        if (left == null || right == null) return null;
        Value[] uni = unifyNumeric(left, right, ctx);
        if (uni == null) return null;
        return new Value(LLVMGenerator.sub(uni[0].name, uni[1].name, uni[0].type), uni[0].type);
    }

    @Override
    public Value visitMulOp(MyLangParser.MulOpContext ctx) {
        Value left  = visit(ctx.mulExpr());
        Value right = visit(ctx.castExpr());
        if (left == null || right == null) return null;
        Value[] uni = unifyNumeric(left, right, ctx);
        if (uni == null) return null;
        return new Value(LLVMGenerator.mul(uni[0].name, uni[1].name, uni[0].type), uni[0].type);
    }

    @Override
    public Value visitDivOp(MyLangParser.DivOpContext ctx) {
        Value left  = visit(ctx.mulExpr());
        Value right = visit(ctx.castExpr());
        if (left == null || right == null) return null;
        Value[] uni = unifyNumeric(left, right, ctx);
        if (uni == null) return null;
        return new Value(LLVMGenerator.div(uni[0].name, uni[1].name, uni[0].type), uni[0].type);
    }

    /** Jednoargumentowy minus: -expr */
    @Override
    public Value visitNegOp(MyLangParser.NegOpContext ctx) {
        Value v = visit(ctx.primaryExpr());
        if (v == null) return null;
        if (!v.type.isNumeric()) {
            semanticError(ctx, "jednoargumentowy minus wymaga typu liczbowego, podano: " + v.type);
            return null;
        }
        return new Value(LLVMGenerator.neg(v.name, v.type), v.type);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  RZUTOWANIE TYPÓW
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Jawne rzutowanie: (typeName) expr
     * Przykłady: (int) 3.14, (real) 5, (bool) x
     * Deleguje do metody cast().
     */
    @Override
    public Value visitCastOp(MyLangParser.CastOpContext ctx) {
        VarType target = resolveType(ctx.typeName());
        Value v = visit(ctx.castExpr());
        if (v == null) return null;
        return cast(v, target, ctx);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ATOMY (LITERAŁY I ODWOŁANIA DO ZMIENNYCH)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Literał całkowity: 42 → Value("42", INT) */
    @Override
    public Value visitIntLit(MyLangParser.IntLitContext ctx) {
        return new Value(ctx.INT().getText(), VarType.INT);
    }

    /** Literał zmiennoprzecinkowy: 3.14 → Value("3.14", REAL) */
    @Override
    public Value visitRealLit(MyLangParser.RealLitContext ctx) {
        return new Value(ctx.REAL().getText(), VarType.REAL);
    }

    /** Literał logiczny true → Value("true", BOOL) */
    @Override
    public Value visitTrueLit(MyLangParser.TrueLitContext ctx) {
        return new Value("true", VarType.BOOL);
    }

    /** Literał logiczny false → Value("false", BOOL) */
    @Override
    public Value visitFalseLit(MyLangParser.FalseLitContext ctx) {
        return new Value("false", VarType.BOOL);
    }

    /**
     * Literał stringowy: "hello"
     * Tworzy globalną stałą LLVM i zwraca wskaźnik do niej.
     * Cudzysłowy są usuwane przed przekazaniem do LLVMGenerator.
     */
    @Override
    public Value visitStringLit(MyLangParser.StringLitContext ctx) {
        String raw = ctx.STRING_LIT().getText();
        String content = raw.substring(1, raw.length() - 1); // usuń cudzysłowy
        String ptr = LLVMGenerator.stringLiteralPtr(content);
        return new Value(ptr, VarType.STRING);
    }

    /**
     * Odwołanie do zmiennej skalarnej: ID
     * Generuje instrukcję load odpowiednią dla typu zmiennej.
     */
    @Override
    public Value visitIdRef(MyLangParser.IdRefContext ctx) {
        String id = ctx.ID().getText();
        if (!variables.containsKey(id)) {
            semanticError(ctx, "niezadeklarowana zmienna '" + id + "'");
            return null;
        }
        VarType t = variables.get(id);
        if (t == VarType.STRING) {
            return new Value(LLVMGenerator.loadString(id), VarType.STRING);
        }
        return new Value(LLVMGenerator.load(id, t), t);
    }

    /**
     * Dostęp do elementu tablicy w wyrażeniu: ID '[' expr ']'
     *
     * 1. Pobierz ArrayInfo dla tablicy
     * 2. Oblicz indeks → skoryguj o startIndex → GEP
     * 3. Load z GEP → zwróć Value(rejestr, elemType)
     */
    @Override
    public Value visitArrayRef(MyLangParser.ArrayRefContext ctx) {
        String id = ctx.ID().getText();
        ArrayInfo info = arrays.get(id);
        if (info == null) {
            semanticError(ctx, "niezadeklarowana tablica '" + id + "'");
            return null;
        }
        Value idxVal = visit(ctx.expr());
        if (idxVal == null) return null;
        if (idxVal.type != VarType.INT) {
            semanticError(ctx, "indeks tablicy musi być typu int, podano: " + idxVal.type);
            return null;
        }
        String adjustedIdx = adjustIndex(idxVal.name, info.startIndex);
        String gep = LLVMGenerator.arrayGEP(id, adjustedIdx, info.elementType, info.size);
        String val = LLVMGenerator.loadFromPtr(gep, info.elementType);
        return new Value(val, info.elementType);
    }

    /** Wyrażenie w nawiasach: (expr) – transparentne przejście. */
    @Override
    public Value visitParenExpr(MyLangParser.ParenExprContext ctx) {
        return visit(ctx.expr());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PRZEPUSZCZENIA (pass-through)
    // ═══════════════════════════════════════════════════════════════════════════
    //
    // Reguły gramatyki mające jedną alternatywę "pass" (np. orExpr → xorExpr)
    // są tylko węzłami hierarchii pierwszeństwa operatorów – nie produkują kodu.
    // Każda metoda po prostu odwiedza jedyne dziecko.

    @Override public Value visitOrPass(MyLangParser.OrPassContext ctx)       { return visit(ctx.xorExpr());    }
    @Override public Value visitXorPass(MyLangParser.XorPassContext ctx)     { return visit(ctx.andExpr());    }
    @Override public Value visitAndPass(MyLangParser.AndPassContext ctx)     { return visit(ctx.notExpr());    }
    @Override public Value visitNotPass(MyLangParser.NotPassContext ctx)     { return visit(ctx.cmpExpr());    }
    @Override public Value visitCmpPass(MyLangParser.CmpPassContext ctx)     { return visit(ctx.addExpr());    }
    @Override public Value visitAddPass(MyLangParser.AddPassContext ctx)     { return visit(ctx.mulExpr());    }
    @Override public Value visitMulPass(MyLangParser.MulPassContext ctx)     { return visit(ctx.castExpr());   }
    @Override public Value visitCastPass(MyLangParser.CastPassContext ctx)   { return visit(ctx.unaryExpr());  }
    @Override public Value visitUnaryPass(MyLangParser.UnaryPassContext ctx) { return visit(ctx.primaryExpr()); }

    /** Przekierowanie expr → orExpr (punkt wejścia hierarchii wyrażeń). */
    @Override
    public Value visitExpr(MyLangParser.ExprContext ctx) {
        return visit(ctx.orExpr());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  METODY POMOCNICZE PRYWATNE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Odczytuje VarType z węzła typeName gramatyki.
     * Używana wszędzie gdzie gramatyka zawiera regułę typeName.
     */
    private VarType resolveType(MyLangParser.TypeNameContext ctx) {
        switch (ctx.getText()) {
            case "int":     return VarType.INT;
            case "real":    return VarType.REAL;
            case "float32": return VarType.FLOAT32;
            case "float64": return VarType.FLOAT64;
            case "bool":    return VarType.BOOL;
            case "string":  return VarType.STRING;
            default:
                System.err.println("Błąd wewnętrzny: nieznany typ '" + ctx.getText() + "'");
                return VarType.UNKNOWN;
        }
    }

    /**
     * Niejawna koercja wartości do docelowego typu.
     * Używana przy deklaracji i przypisaniu (gdzie typy muszą być zgodne).
     *
     * Dozwolone konwersje (bezstratne lub z możliwą utratą precyzji):
     *   int    → real, float64, float32
     *   float32→ real, float64  (poszerzenie, bezstratne)
     *   real/float64 → float32  (zawężenie, możliwa utrata precyzji)
     *   real   ↔ float64        (synonimy, brak konwersji w LLVM)
     *
     * @return skonwertowane Value lub null (błąd semantyczny)
     */
    private Value coerce(Value v, VarType target, ParserRuleContext ctx) {
        if (v.type == target) return v;

        if (v.type == VarType.INT) {
            if (target == VarType.REAL || target == VarType.FLOAT64)
                return new Value(LLVMGenerator.intToDouble(v.name), target);
            if (target == VarType.FLOAT32)
                return new Value(LLVMGenerator.intToFloat(v.name), VarType.FLOAT32);
        }
        if (v.type == VarType.FLOAT32 && (target == VarType.REAL || target == VarType.FLOAT64))
            return new Value(LLVMGenerator.floatToDouble(v.name), target);
        if ((v.type == VarType.REAL || v.type == VarType.FLOAT64) && target == VarType.FLOAT32)
            return new Value(LLVMGenerator.doubleToFloat(v.name), VarType.FLOAT32);
        // real ↔ float64: oba to double w LLVM – tylko zmiana etykiety typu
        if ((v.type == VarType.REAL && target == VarType.FLOAT64) ||
            (v.type == VarType.FLOAT64 && target == VarType.REAL))
            return new Value(v.name, target);

        semanticError(ctx, "niezgodność typów: oczekiwano " + target + ", podano " + v.type);
        return null;
    }

    /**
     * Jawne rzutowanie (operator cast): dowolne konwersje typów.
     * Obsługuje więcej przypadków niż coerce (np. float → int, int → bool).
     *
     * @return skonwertowane Value lub null (błąd semantyczny)
     */
    private Value cast(Value v, VarType target, ParserRuleContext ctx) {
        if (v.type == target) return v;

        if (target == VarType.INT) {
            if (v.type.isFloat())           return new Value(floatOrDoubleToInt(v), VarType.INT);
            if (v.type == VarType.BOOL)     return new Value(LLVMGenerator.boolToInt(v.name), VarType.INT);
        }
        if (target == VarType.REAL || target == VarType.FLOAT64) {
            if (v.type == VarType.INT)      return new Value(LLVMGenerator.intToDouble(v.name), target);
            if (v.type == VarType.FLOAT32)  return new Value(LLVMGenerator.floatToDouble(v.name), target);
            if (v.type == VarType.REAL || v.type == VarType.FLOAT64)
                                            return new Value(v.name, target);
            if (v.type == VarType.BOOL)     return new Value(LLVMGenerator.boolToFloat(v.name, target), target);
        }
        if (target == VarType.FLOAT32) {
            if (v.type == VarType.INT)      return new Value(LLVMGenerator.intToFloat(v.name), VarType.FLOAT32);
            if (v.type == VarType.REAL || v.type == VarType.FLOAT64)
                                            return new Value(LLVMGenerator.doubleToFloat(v.name), VarType.FLOAT32);
            if (v.type == VarType.BOOL)     return new Value(LLVMGenerator.boolToFloat(v.name, VarType.FLOAT32), VarType.FLOAT32);
        }
        if (target == VarType.BOOL) {
            if (v.type == VarType.INT)      return new Value(LLVMGenerator.intToBool(v.name), VarType.BOOL);
            if (v.type.isFloat())           return new Value(LLVMGenerator.floatToBool(v.name, v.type), VarType.BOOL);
        }
        semanticError(ctx, "niedozwolone rzutowanie: " + v.type + " → " + target);
        return null;
    }

    /**
     * Wybiera doubleToInt lub floatToInt w zależności od rzeczywistego typu.
     */
    private String floatOrDoubleToInt(Value v) {
        if (v.type == VarType.FLOAT32) return LLVMGenerator.floatToInt(v.name);
        return LLVMGenerator.doubleToInt(v.name);
    }

    /**
     * Unifikuje dwa operandy numeryczne do wspólnego szerszego typu.
     *
     * Hierarchia: int < float32 < real/float64
     * Słabszy typ jest promowany do silniejszego.
     *
     * Przykłady:
     *   (INT, FLOAT32)  → (FLOAT32, FLOAT32)  – int promoted to float
     *   (FLOAT32, REAL) → (REAL, REAL)         – float32 promoted to double
     *   (INT, REAL)     → (REAL, REAL)         – int promoted to double
     *   (REAL, FLOAT64) → (REAL, REAL)         – oba to double, bez konwersji
     *
     * @return para [left, right] po ewentualnej promocji, lub null przy błędzie
     */
    private Value[] unifyNumeric(Value left, Value right, ParserRuleContext ctx) {
        if (!left.type.isNumeric() || !right.type.isNumeric()) {
            if (left.type == VarType.STRING && right.type == VarType.STRING)
                semanticError(ctx, "konkatenacja stringów nie jest obsługiwana");
            else
                semanticError(ctx, "operacja arytmetyczna na typach " + left.type + " i " + right.type);
            return null;
        }
        if (left.type == right.type) return new Value[]{left, right};

        // int ↔ float32
        if (left.type == VarType.INT && right.type == VarType.FLOAT32)
            return new Value[]{new Value(LLVMGenerator.intToFloat(left.name), VarType.FLOAT32), right};
        if (left.type == VarType.FLOAT32 && right.type == VarType.INT)
            return new Value[]{left, new Value(LLVMGenerator.intToFloat(right.name), VarType.FLOAT32)};

        // int ↔ real/float64
        if (left.type == VarType.INT && (right.type == VarType.REAL || right.type == VarType.FLOAT64))
            return new Value[]{new Value(LLVMGenerator.intToDouble(left.name), right.type), right};
        if ((left.type == VarType.REAL || left.type == VarType.FLOAT64) && right.type == VarType.INT)
            return new Value[]{left, new Value(LLVMGenerator.intToDouble(right.name), left.type)};

        // float32 ↔ real/float64
        if (left.type == VarType.FLOAT32 && (right.type == VarType.REAL || right.type == VarType.FLOAT64))
            return new Value[]{new Value(LLVMGenerator.floatToDouble(left.name), right.type), right};
        if ((left.type == VarType.REAL || left.type == VarType.FLOAT64) && right.type == VarType.FLOAT32)
            return new Value[]{left, new Value(LLVMGenerator.floatToDouble(right.name), left.type)};

        // real ↔ float64 (oba to double – tylko zmień etykietę)
        if ((left.type == VarType.REAL || left.type == VarType.FLOAT64) &&
            (right.type == VarType.REAL || right.type == VarType.FLOAT64)) {
            VarType common = (left.type == VarType.REAL) ? VarType.REAL : VarType.FLOAT64;
            return new Value[]{new Value(left.name, common), new Value(right.name, common)};
        }

        semanticError(ctx, "nie można ujednolicić typów: " + left.type + " i " + right.type);
        return null;
    }

    /**
     * Zwraca operator icmp LLVM dla porównania liczb całkowitych.
     * Wszystkie operatory są signed (s-prefiks), bo int w MyLang jest signed.
     */
    private String icmpOp(String op) {
        switch (op) {
            case "==": return "eq";
            case "!=": return "ne";
            case "<":  return "slt";
            case ">":  return "sgt";
            case "<=": return "sle";
            case ">=": return "sge";
            default:   return "eq";
        }
    }

    /**
     * Zwraca operator fcmp LLVM dla porównania zmiennoprzecinkowego.
     * Ordered (o-prefiks): wynik = false gdy któryś operand jest NaN.
     */
    private String fcmpOp(String op) {
        switch (op) {
            case "==": return "oeq";
            case "!=": return "one";
            case "<":  return "olt";
            case ">":  return "ogt";
            case "<=": return "ole";
            case ">=": return "oge";
            default:   return "oeq";
        }
    }

    /**
     * Oblicza GEP do bufora stringa i zwraca rejestr wskaźnika i8*.
     *
     * Schemat pamięci string:
     *   %id     : i8**         → wskaźnik do wskaźnika
     *   %id_buf : [256 x i8]  → rzeczywisty bufor
     *   GEP: [256 x i8]* → i8* (wskaźnik do 1. bajtu bufora)
     *
     * Używane przy strcpy do skopiowania wartości do zmiennej string.
     */
    private String loadStringBufPtr(String id) {
        String r = LLVMGenerator.nextReg();
        LLVMGenerator.main_text += "  " + r
            + " = getelementptr inbounds [256 x i8], [256 x i8]* %"
            + id + "_buf, i32 0, i32 0\n";
        return r;
    }

    /**
     * Koryguje indeks tablicy o wartość startIndex.
     *
     * LLVM zawsze używa indeksów 0-based dla GEP. Jeśli tablica ma zakres
     * startIndex..endIndex, użytkownik używa indeksów od startIndex, ale
     * w LLVM przechowujemy od 0. Korekta: adjusted = user_index - startIndex.
     *
     * Dla tablic 0-based (startIndex=0): korekta nie jest potrzebna,
     * zwracamy idxReg bez emisji dodatkowej instrukcji.
     */
    private String adjustIndex(String idxReg, int startIndex) {
        if (startIndex == 0) return idxReg;
        String r = LLVMGenerator.nextReg();
        LLVMGenerator.main_text += "  " + r + " = sub i32 " + idxReg + ", " + startIndex + "\n";
        return r;
    }

    /**
     * Emituje odpowiednią instrukcję printf dla danej wartości.
     * Wywoływana zarówno przez visitPrintIdStat jak i visitPrintStat.
     */
    private void emitPrint(Value val, ParserRuleContext ctx) {
        switch (val.type) {
            case INT:             LLVMGenerator.printf_i32(val.name);    break;
            case REAL:
            case FLOAT64:         LLVMGenerator.printf_double(val.name); break;
            case FLOAT32:         LLVMGenerator.printf_float(val.name);  break;
            case BOOL:            LLVMGenerator.printf_bool(val.name);   break;
            case STRING:          LLVMGenerator.printf_string(val.name); break;
            default:              semanticError(ctx, "nie można wypisać wartości typu " + val.type);
        }
    }

    /**
     * Sprawdza, czy dana nazwa jest już zadeklarowana (jako zmienna lub tablica).
     * Używane do wykrywania podwójnych deklaracji.
     */
    private boolean isDeclared(String id) {
        return variables.containsKey(id) || arrays.containsKey(id);
    }

    /** Zgłasza błąd semantyczny i inkrementuje licznik błędów. */
    private void semanticError(ParserRuleContext ctx, String msg) {
        errors++;
        System.err.println("Błąd semantyczny, linia "
            + ctx.getStart().getLine() + ": " + msg);
    }

    /** Czy wystąpił jakikolwiek błąd semantyczny? */
    public boolean hasErrors() {
        return errors > 0;
    }
}
