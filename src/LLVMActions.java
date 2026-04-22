import org.antlr.v4.runtime.ParserRuleContext;

import java.util.HashMap;
import java.util.Map;

public class LLVMActions extends MyLangBaseVisitor<Value> {

    private final Map<String, VarType> variables = new HashMap<>();
    private final Map<String, ArrayInfo> arrays = new HashMap<>();
    private int errors = 0;

    @Override
    public Value visitProg(MyLangParser.ProgContext ctx) {
        visitChildren(ctx);
        if (errors == 0) System.out.print(LLVMGenerator.generate());
        return null;
    }

    @Override
    public Value visitVarDecl(MyLangParser.VarDeclContext ctx) {
        String id = ctx.ID().getText();
        VarType t = resolveType(ctx.typeName());

        if (isDeclared(id)) {
            semanticError(ctx, "zmienna '" + id + "' juz zostala zadeklarowana");
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
                    semanticError(ctx, "inicjalizacja string wymaga wartosci string");
                    return null;
                }
                String bufPtr = stringBufPtr(id);
                String strcpyReg = LLVMGenerator.nextReg();
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

    @Override
    public Value visitArrayDecl(MyLangParser.ArrayDeclContext ctx) {
        VarType elemType = resolveType(ctx.typeName());
        int size = Integer.parseInt(ctx.INT().getText());
        String id = ctx.ID().getText();

        if (size <= 0) {
            semanticError(ctx, "rozmiar tablicy musi byc > 0");
            return null;
        }
        if (isDeclared(id)) {
            semanticError(ctx, "nazwa '" + id + "' juz jest zadeklarowana");
            return null;
        }

        arrays.put(id, new ArrayInfo(elemType, size));
        LLVMGenerator.declareArray(id, elemType, size);
        return null;
    }

    @Override
    public Value visitRangedArrayDecl(MyLangParser.RangedArrayDeclContext ctx) {
        VarType elemType = resolveType(ctx.typeName());
        int start = Integer.parseInt(ctx.INT(0).getText());
        int end = Integer.parseInt(ctx.INT(1).getText());
        String id = ctx.ID().getText();

        if (start > end) {
            semanticError(ctx, "nieprawidlowy zakres: " + start + ".." + end);
            return null;
        }
        if (isDeclared(id)) {
            semanticError(ctx, "nazwa '" + id + "' juz jest zadeklarowana");
            return null;
        }

        ArrayInfo info = new ArrayInfo(elemType, start, end);
        arrays.put(id, info);
        LLVMGenerator.declareArray(id, elemType, info.size);
        return null;
    }

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
                semanticError(ctx, "przypisanie: oczekiwano string");
                return null;
            }
            String bufPtr = stringBufPtr(id);
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
            semanticError(ctx, "indeks musi byc int");
            return null;
        }

        String gep = LLVMGenerator.arrayGEP(id, adjustIndex(idxVal.name, info.startIndex), info.elementType, info.size);

        Value val = visit(ctx.expr(1));
        if (val == null) return null;
        Value coerced = coerce(val, info.elementType, ctx);
        if (coerced == null) return null;

        LLVMGenerator.storeToPtr(gep, coerced.name, info.elementType);
        return null;
    }

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

    @Override
    public Value visitPrintStat(MyLangParser.PrintStatContext ctx) {
        Value val = visit(ctx.expr());
        if (val == null) return null;
        emitPrint(val, ctx);
        return null;
    }

    @Override
    public Value visitReadStat(MyLangParser.ReadStatContext ctx) {
        String id = ctx.ID().getText();
        if (!variables.containsKey(id)) {
            semanticError(ctx, "niezadeklarowana zmienna '" + id + "'");
            return null;
        }
        switch (variables.get(id)) {
            case INT:
                LLVMGenerator.scanf_i32(id);
                break;
            case REAL:
            case FLOAT64:
                LLVMGenerator.scanf_double(id);
                break;
            case FLOAT32:
                LLVMGenerator.scanf_float(id);
                break;
            case BOOL:
                LLVMGenerator.scanf_bool(id);
                break;
            case STRING:
                LLVMGenerator.scanf_string(id);
                break;
            default:
                semanticError(ctx, "nie mozna wczytac typu " + variables.get(id));
        }
        return null;
    }

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
            semanticError(ctx, "indeks musi byc int");
            return null;
        }

        String gep = LLVMGenerator.arrayGEP(id, adjustIndex(idxVal.name, info.startIndex), info.elementType, info.size);
        LLVMGenerator.scanf_into_ptr(gep, info.elementType);
        return null;
    }

    @Override
    public Value visitOrOp(MyLangParser.OrOpContext ctx) {
        Value left = visit(ctx.orExpr());
        if (left == null) return null;
        if (left.type != VarType.BOOL) {
            semanticError(ctx, "OR wymaga bool");
            return null;
        }

        int id = LLVMGenerator.labelCount++;
        String evalLabel = "or_eval_" + id;
        String trueLabel = "or_true_" + id;
        String endLabel = "or_end_" + id;

        LLVMGenerator.condBr(left.name, trueLabel, evalLabel);
        LLVMGenerator.emitLabel(evalLabel);

        Value right = visit(ctx.xorExpr());
        if (right == null) return null;
        if (right.type != VarType.BOOL) {
            semanticError(ctx, "OR wymaga bool");
            return null;
        }

        String evalEndBlock = LLVMGenerator.currentBlock;
        LLVMGenerator.br(endLabel);
        LLVMGenerator.emitLabel(trueLabel);
        LLVMGenerator.br(endLabel);
        LLVMGenerator.emitLabel(endLabel);

        return new Value(LLVMGenerator.phi_i1(right.name, evalEndBlock, "true", trueLabel), VarType.BOOL);
    }

    @Override
    public Value visitXorOp(MyLangParser.XorOpContext ctx) {
        Value left = visit(ctx.xorExpr());
        Value right = visit(ctx.andExpr());
        if (left == null || right == null) return null;
        if (left.type != VarType.BOOL || right.type != VarType.BOOL) {
            semanticError(ctx, "XOR wymaga bool");
            return null;
        }
        return new Value(LLVMGenerator.xor_i1(left.name, right.name), VarType.BOOL);
    }

    @Override
    public Value visitAndOp(MyLangParser.AndOpContext ctx) {
        Value left = visit(ctx.andExpr());
        if (left == null) return null;
        if (left.type != VarType.BOOL) {
            semanticError(ctx, "AND wymaga bool");
            return null;
        }

        int id = LLVMGenerator.labelCount++;
        String evalLabel = "and_eval_" + id;
        String falseLabel = "and_false_" + id;
        String endLabel = "and_end_" + id;

        LLVMGenerator.condBr(left.name, evalLabel, falseLabel);
        LLVMGenerator.emitLabel(evalLabel);

        Value right = visit(ctx.notExpr());
        if (right == null) return null;
        if (right.type != VarType.BOOL) {
            semanticError(ctx, "AND wymaga bool");
            return null;
        }

        String evalEndBlock = LLVMGenerator.currentBlock;
        LLVMGenerator.br(endLabel);
        LLVMGenerator.emitLabel(falseLabel);
        LLVMGenerator.br(endLabel);
        LLVMGenerator.emitLabel(endLabel);

        return new Value(LLVMGenerator.phi_i1(right.name, evalEndBlock, "false", falseLabel), VarType.BOOL);
    }

    @Override
    public Value visitNotOp(MyLangParser.NotOpContext ctx) {
        Value v = visit(ctx.notExpr());
        if (v == null) return null;
        if (v.type != VarType.BOOL) {
            semanticError(ctx, "NOT wymaga bool");
            return null;
        }
        return new Value(LLVMGenerator.not_i1(v.name), VarType.BOOL);
    }

    @Override
    public Value visitEqOp(MyLangParser.EqOpContext ctx) {
        return doCmp(ctx, ctx.addExpr(0), ctx.addExpr(1), "==");
    }

    @Override
    public Value visitNeOp(MyLangParser.NeOpContext ctx) {
        return doCmp(ctx, ctx.addExpr(0), ctx.addExpr(1), "!=");
    }

    @Override
    public Value visitLtOp(MyLangParser.LtOpContext ctx) {
        return doCmp(ctx, ctx.addExpr(0), ctx.addExpr(1), "<");
    }

    @Override
    public Value visitGtOp(MyLangParser.GtOpContext ctx) {
        return doCmp(ctx, ctx.addExpr(0), ctx.addExpr(1), ">");
    }

    @Override
    public Value visitLeOp(MyLangParser.LeOpContext ctx) {
        return doCmp(ctx, ctx.addExpr(0), ctx.addExpr(1), "<=");
    }

    @Override
    public Value visitGeOp(MyLangParser.GeOpContext ctx) {
        return doCmp(ctx, ctx.addExpr(0), ctx.addExpr(1), ">=");
    }

    private Value doCmp(ParserRuleContext ctx,
                        MyLangParser.AddExprContext lCtx,
                        MyLangParser.AddExprContext rCtx,
                        String op) {
        Value left = visit(lCtx);
        Value right = visit(rCtx);
        if (left == null || right == null) return null;
        Value[] uni = unifyNumeric(left, right, ctx);
        if (uni == null) return null;
        String result = (uni[0].type == VarType.INT)
                ? LLVMGenerator.icmp(icmpOp(op), uni[0].name, uni[1].name)
                : LLVMGenerator.fcmp(fcmpOp(op), uni[0].name, uni[1].name, uni[0].type);
        return new Value(result, VarType.BOOL);
    }

    @Override
    public Value visitAddOp(MyLangParser.AddOpContext ctx) {
        Value left = visit(ctx.addExpr());
        Value right = visit(ctx.mulExpr());
        if (left == null || right == null) return null;
        Value[] uni = unifyNumeric(left, right, ctx);
        if (uni == null) return null;
        return new Value(LLVMGenerator.add(uni[0].name, uni[1].name, uni[0].type), uni[0].type);
    }

    @Override
    public Value visitSubOp(MyLangParser.SubOpContext ctx) {
        Value left = visit(ctx.addExpr());
        Value right = visit(ctx.mulExpr());
        if (left == null || right == null) return null;
        Value[] uni = unifyNumeric(left, right, ctx);
        if (uni == null) return null;
        return new Value(LLVMGenerator.sub(uni[0].name, uni[1].name, uni[0].type), uni[0].type);
    }

    @Override
    public Value visitMulOp(MyLangParser.MulOpContext ctx) {
        Value left = visit(ctx.mulExpr());
        Value right = visit(ctx.castExpr());
        if (left == null || right == null) return null;
        Value[] uni = unifyNumeric(left, right, ctx);
        if (uni == null) return null;
        return new Value(LLVMGenerator.mul(uni[0].name, uni[1].name, uni[0].type), uni[0].type);
    }

    @Override
    public Value visitDivOp(MyLangParser.DivOpContext ctx) {
        Value left = visit(ctx.mulExpr());
        Value right = visit(ctx.castExpr());
        if (left == null || right == null) return null;
        Value[] uni = unifyNumeric(left, right, ctx);
        if (uni == null) return null;
        return new Value(LLVMGenerator.div(uni[0].name, uni[1].name, uni[0].type), uni[0].type);
    }

    @Override
    public Value visitNegOp(MyLangParser.NegOpContext ctx) {
        Value v = visit(ctx.primaryExpr());
        if (v == null) return null;
        if (!v.type.isNumeric()) {
            semanticError(ctx, "minus wymaga typu liczbowego");
            return null;
        }
        return new Value(LLVMGenerator.neg(v.name, v.type), v.type);
    }

    @Override
    public Value visitCastOp(MyLangParser.CastOpContext ctx) {
        Value v = visit(ctx.castExpr());
        if (v == null) return null;
        return cast(v, resolveType(ctx.typeName()), ctx);
    }

    @Override
    public Value visitIntLit(MyLangParser.IntLitContext ctx) {
        return new Value(ctx.INT().getText(), VarType.INT);
    }

    @Override
    public Value visitRealLit(MyLangParser.RealLitContext ctx) {
        return new Value(ctx.REAL().getText(), VarType.REAL);
    }

    @Override
    public Value visitTrueLit(MyLangParser.TrueLitContext ctx) {
        return new Value("true", VarType.BOOL);
    }

    @Override
    public Value visitFalseLit(MyLangParser.FalseLitContext ctx) {
        return new Value("false", VarType.BOOL);
    }

    @Override
    public Value visitStringLit(MyLangParser.StringLitContext ctx) {
        String raw = ctx.STRING_LIT().getText();
        String content = raw.substring(1, raw.length() - 1);
        return new Value(LLVMGenerator.stringLiteralPtr(content), VarType.STRING);
    }

    @Override
    public Value visitIdRef(MyLangParser.IdRefContext ctx) {
        String id = ctx.ID().getText();
        if (!variables.containsKey(id)) {
            semanticError(ctx, "niezadeklarowana zmienna '" + id + "'");
            return null;
        }
        VarType t = variables.get(id);
        if (t == VarType.STRING) return new Value(LLVMGenerator.loadString(id), VarType.STRING);
        return new Value(LLVMGenerator.load(id, t), t);
    }

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
            semanticError(ctx, "indeks musi byc int");
            return null;
        }

        String gep = LLVMGenerator.arrayGEP(id, adjustIndex(idxVal.name, info.startIndex), info.elementType, info.size);
        return new Value(LLVMGenerator.loadFromPtr(gep, info.elementType), info.elementType);
    }

    @Override
    public Value visitParenExpr(MyLangParser.ParenExprContext ctx) {
        return visit(ctx.expr());
    }

    @Override
    public Value visitOrPass(MyLangParser.OrPassContext ctx) {
        return visit(ctx.xorExpr());
    }

    @Override
    public Value visitXorPass(MyLangParser.XorPassContext ctx) {
        return visit(ctx.andExpr());
    }

    @Override
    public Value visitAndPass(MyLangParser.AndPassContext ctx) {
        return visit(ctx.notExpr());
    }

    @Override
    public Value visitNotPass(MyLangParser.NotPassContext ctx) {
        return visit(ctx.cmpExpr());
    }

    @Override
    public Value visitCmpPass(MyLangParser.CmpPassContext ctx) {
        return visit(ctx.addExpr());
    }

    @Override
    public Value visitAddPass(MyLangParser.AddPassContext ctx) {
        return visit(ctx.mulExpr());
    }

    @Override
    public Value visitMulPass(MyLangParser.MulPassContext ctx) {
        return visit(ctx.castExpr());
    }

    @Override
    public Value visitCastPass(MyLangParser.CastPassContext ctx) {
        return visit(ctx.unaryExpr());
    }

    @Override
    public Value visitUnaryPass(MyLangParser.UnaryPassContext ctx) {
        return visit(ctx.primaryExpr());
    }

    @Override
    public Value visitExpr(MyLangParser.ExprContext ctx) {
        return visit(ctx.orExpr());
    }

    private VarType resolveType(MyLangParser.TypeNameContext ctx) {
        switch (ctx.getText()) {
            case "int":
                return VarType.INT;
            case "real":
                return VarType.REAL;
            case "float32":
                return VarType.FLOAT32;
            case "float64":
                return VarType.FLOAT64;
            case "bool":
                return VarType.BOOL;
            case "string":
                return VarType.STRING;
            default:
                return VarType.UNKNOWN;
        }
    }

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
        if ((v.type == VarType.REAL && target == VarType.FLOAT64) ||
                (v.type == VarType.FLOAT64 && target == VarType.REAL))
            return new Value(v.name, target);
        semanticError(ctx, "niezgodnosc typow: oczekiwano " + target + ", podano " + v.type);
        return null;
    }

    private Value cast(Value v, VarType target, ParserRuleContext ctx) {
        if (v.type == target) return v;
        if (target == VarType.INT) {
            if (v.type.isFloat()) return new Value(v.type == VarType.FLOAT32
                    ? LLVMGenerator.floatToInt(v.name) : LLVMGenerator.doubleToInt(v.name), VarType.INT);
            if (v.type == VarType.BOOL) return new Value(LLVMGenerator.boolToInt(v.name), VarType.INT);
        }
        if (target == VarType.REAL || target == VarType.FLOAT64) {
            if (v.type == VarType.INT) return new Value(LLVMGenerator.intToDouble(v.name), target);
            if (v.type == VarType.FLOAT32) return new Value(LLVMGenerator.floatToDouble(v.name), target);
            if (v.type == VarType.REAL || v.type == VarType.FLOAT64) return new Value(v.name, target);
            if (v.type == VarType.BOOL) return new Value(LLVMGenerator.boolToFloat(v.name, target), target);
        }
        if (target == VarType.FLOAT32) {
            if (v.type == VarType.INT) return new Value(LLVMGenerator.intToFloat(v.name), VarType.FLOAT32);
            if (v.type == VarType.REAL || v.type == VarType.FLOAT64)
                return new Value(LLVMGenerator.doubleToFloat(v.name), VarType.FLOAT32);
            if (v.type == VarType.BOOL)
                return new Value(LLVMGenerator.boolToFloat(v.name, VarType.FLOAT32), VarType.FLOAT32);
        }
        if (target == VarType.BOOL) {
            if (v.type == VarType.INT) return new Value(LLVMGenerator.intToBool(v.name), VarType.BOOL);
            if (v.type.isFloat()) return new Value(LLVMGenerator.floatToBool(v.name, v.type), VarType.BOOL);
        }
        semanticError(ctx, "niedozwolone rzutowanie: " + v.type + " -> " + target);
        return null;
    }

    private Value[] unifyNumeric(Value left, Value right, ParserRuleContext ctx) {
        if (!left.type.isNumeric() || !right.type.isNumeric()) {
            semanticError(ctx, "operacja arytmetyczna na typach " + left.type + " i " + right.type);
            return null;
        }
        if (left.type == right.type) return new Value[]{left, right};

        if (left.type == VarType.INT && right.type == VarType.FLOAT32)
            return new Value[]{new Value(LLVMGenerator.intToFloat(left.name), VarType.FLOAT32), right};
        if (left.type == VarType.FLOAT32 && right.type == VarType.INT)
            return new Value[]{left, new Value(LLVMGenerator.intToFloat(right.name), VarType.FLOAT32)};
        if (left.type == VarType.INT && (right.type == VarType.REAL || right.type == VarType.FLOAT64))
            return new Value[]{new Value(LLVMGenerator.intToDouble(left.name), right.type), right};
        if ((left.type == VarType.REAL || left.type == VarType.FLOAT64) && right.type == VarType.INT)
            return new Value[]{left, new Value(LLVMGenerator.intToDouble(right.name), left.type)};
        if (left.type == VarType.FLOAT32 && (right.type == VarType.REAL || right.type == VarType.FLOAT64))
            return new Value[]{new Value(LLVMGenerator.floatToDouble(left.name), right.type), right};
        if ((left.type == VarType.REAL || left.type == VarType.FLOAT64) && right.type == VarType.FLOAT32)
            return new Value[]{left, new Value(LLVMGenerator.floatToDouble(right.name), left.type)};
        if ((left.type == VarType.REAL || left.type == VarType.FLOAT64) &&
                (right.type == VarType.REAL || right.type == VarType.FLOAT64)) {
            VarType common = (left.type == VarType.REAL) ? VarType.REAL : VarType.FLOAT64;
            return new Value[]{new Value(left.name, common), new Value(right.name, common)};
        }
        semanticError(ctx, "nie mozna ujednolicic typow: " + left.type + " i " + right.type);
        return null;
    }

    private String icmpOp(String op) {
        switch (op) {
            case "==":
                return "eq";
            case "!=":
                return "ne";
            case "<":
                return "slt";
            case ">":
                return "sgt";
            case "<=":
                return "sle";
            case ">=":
                return "sge";
            default:
                return "eq";
        }
    }

    private String fcmpOp(String op) {
        switch (op) {
            case "==":
                return "oeq";
            case "!=":
                return "one";
            case "<":
                return "olt";
            case ">":
                return "ogt";
            case "<=":
                return "ole";
            case ">=":
                return "oge";
            default:
                return "oeq";
        }
    }

    private String stringBufPtr(String id) {
        String r = LLVMGenerator.nextReg();
        LLVMGenerator.main_text += "  " + r
                + " = getelementptr inbounds [256 x i8], [256 x i8]* %"
                + id + "_buf, i32 0, i32 0\n";
        return r;
    }

    private String adjustIndex(String idxReg, int startIndex) {
        if (startIndex == 0) return idxReg;
        String r = LLVMGenerator.nextReg();
        LLVMGenerator.main_text += "  " + r + " = sub i32 " + idxReg + ", " + startIndex + "\n";
        return r;
    }

    private void emitPrint(Value val, ParserRuleContext ctx) {
        switch (val.type) {
            case INT:
                LLVMGenerator.printf_i32(val.name);
                break;
            case REAL:
            case FLOAT64:
                LLVMGenerator.printf_double(val.name);
                break;
            case FLOAT32:
                LLVMGenerator.printf_float(val.name);
                break;
            case BOOL:
                LLVMGenerator.printf_bool(val.name);
                break;
            case STRING:
                LLVMGenerator.printf_string(val.name);
                break;
            default:
                semanticError(ctx, "nie mozna wypisac typu " + val.type);
        }
    }

    private boolean isDeclared(String id) {
        return variables.containsKey(id) || arrays.containsKey(id);
    }

    private void semanticError(ParserRuleContext ctx, String msg) {
        errors++;
        System.err.println("Blad semantyczny, linia " + ctx.getStart().getLine() + ": " + msg);
    }

    public boolean hasErrors() {
        return errors > 0;
    }
}
