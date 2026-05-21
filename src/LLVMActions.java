import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LLVMActions extends MyLangBaseVisitor<Value> {

    private Map<String, VarType> variables = new HashMap<>();
    private Map<String, String> llvmVarName = new HashMap<>();
    private Map<String, ArrayInfo> arrays = new HashMap<>();
    private Map<String, MatrixInfo> matrices = new HashMap<>();
    private Map<String, String> structVars = new HashMap<>();

    private Map<String, VarType> savedVariables;
    private Map<String, String> savedLlvmVarName;
    private Map<String, ArrayInfo> savedArrays;
    private Map<String, MatrixInfo> savedMatrices;
    private Map<String, String> savedStructVars;

    private final Map<String, FuncInfo> functions = new HashMap<>();
    private final Map<String, StructInfo> structs = new HashMap<>();

    private FuncInfo currentFunc = null;
    private String currentClassName = null;

    private int errors = 0;
    private int varCounter = 0;

    @Override
    public Value visitProg(MyLangParser.ProgContext ctx) {
        visitChildren(ctx);
        if (errors == 0) System.out.print(LLVMGenerator.generate());
        return null;
    }

    @Override
    public Value visitFuncDeclRule(MyLangParser.FuncDeclRuleContext ctx) {
        String name = ctx.ID().getText();
        VarType retType = resolveReturnType(ctx.returnTypeName());
        List<String> paramNames = new ArrayList<>();
        List<VarType> paramTypes = new ArrayList<>();

        if (ctx.paramList() != null) {
            for (MyLangParser.ParamContext p : ctx.paramList().param()) {
                paramNames.add(p.ID().getText());
                paramTypes.add(resolveTypeName(p.typeName()));
            }
        }

        FuncInfo info = new FuncInfo(name, retType, paramNames, paramTypes);
        functions.put(name, info);

        savedVariables = variables;
        savedLlvmVarName = llvmVarName;
        savedArrays = arrays;
        savedMatrices = matrices;
        savedStructVars = structVars;
        variables = new HashMap<>();
        llvmVarName = new HashMap<>();
        arrays = new HashMap<>();
        matrices = new HashMap<>();
        structVars = new HashMap<>();
        currentFunc = info;

        for (int i = 0; i < paramNames.size(); i++) {
            String pn = paramNames.get(i);
            variables.put(pn, paramTypes.get(i));
            llvmVarName.put(pn, "%" + pn);
        }

        LLVMGenerator.beginFunction(name, retType, paramNames, paramTypes);
        visit(ctx.block());
        LLVMGenerator.endFunction(retType);

        currentFunc = null;
        variables = savedVariables;
        llvmVarName = savedLlvmVarName;
        arrays = savedArrays;
        matrices = savedMatrices;
        structVars = savedStructVars;
        return null;
    }

    @Override
    public Value visitStructDeclRule(MyLangParser.StructDeclRuleContext ctx) {
        String name = ctx.ID().getText();
        List<String> fieldNames = new ArrayList<>();
        List<VarType> fieldTypes = new ArrayList<>();

        for (MyLangParser.FieldDeclContext f : ctx.fieldDecl()) {
            VarType ft = resolveTypeName(f.typeName());
            if (ft == VarType.STRUCT) {
                semanticError(ctx, "zagniezdzone struktury nie sa obslugiwane");
                return null;
            }
            fieldNames.add(f.ID().getText());
            fieldTypes.add(ft);
        }

        StructInfo si = new StructInfo(name, fieldNames, fieldTypes, false);
        structs.put(name, si);
        LLVMGenerator.declareStructType(name, fieldNames, fieldTypes);
        return null;
    }

    @Override
    public Value visitClassDeclRule(MyLangParser.ClassDeclRuleContext ctx) {
        String name = ctx.ID().getText();
        List<String> fieldNames = new ArrayList<>();
        List<VarType> fieldTypes = new ArrayList<>();

        for (MyLangParser.ClassMemberContext m : ctx.classMember()) {
            if (m instanceof MyLangParser.ClassMemberFieldContext) {
                MyLangParser.FieldDeclContext f =
                        ((MyLangParser.ClassMemberFieldContext) m).fieldDecl();
                VarType ft = resolveTypeName(f.typeName());
                fieldNames.add(f.ID().getText());
                fieldTypes.add(ft);
            }
        }

        StructInfo si = new StructInfo(name, fieldNames, fieldTypes, true);
        structs.put(name, si);
        LLVMGenerator.declareStructType(name, fieldNames, fieldTypes);

        currentClassName = name;
        savedVariables = variables;
        savedLlvmVarName = llvmVarName;
        savedArrays = arrays;
        savedMatrices = matrices;
        savedStructVars = structVars;

        for (MyLangParser.ClassMemberContext m : ctx.classMember()) {
            if (m instanceof MyLangParser.ClassMemberMethodContext) {
                MyLangParser.MethodDeclContext md =
                        ((MyLangParser.ClassMemberMethodContext) m).methodDecl();
                visitMethodDecl(md, name, si);
            }
        }

        currentClassName = null;
        variables = savedVariables;
        llvmVarName = savedLlvmVarName;
        arrays = savedArrays;
        matrices = savedMatrices;
        structVars = savedStructVars;
        return null;
    }

    private void visitMethodDecl(MyLangParser.MethodDeclContext ctx,
                                 String className, StructInfo si) {
        String methodName = ctx.ID().getText();
        VarType retType = resolveReturnType(ctx.returnTypeName());
        List<String> paramNames = new ArrayList<>();
        List<VarType> paramTypes = new ArrayList<>();

        if (ctx.paramList() != null) {
            for (MyLangParser.ParamContext p : ctx.paramList().param()) {
                paramNames.add(p.ID().getText());
                paramTypes.add(resolveTypeName(p.typeName()));
            }
        }

        String llvmName = className + "_" + methodName;
        FuncInfo info = new FuncInfo(llvmName, retType, paramNames, paramTypes);
        si.methods.put(methodName, info);
        functions.put(llvmName, info);

        variables = new HashMap<>();
        llvmVarName = new HashMap<>();
        arrays = new HashMap<>();
        matrices = new HashMap<>();
        structVars = new HashMap<>();
        structVars.put("self", className);
        currentFunc = info;

        for (int i = 0; i < paramNames.size(); i++) {
            String pn = paramNames.get(i);
            variables.put(pn, paramTypes.get(i));
            llvmVarName.put(pn, "%" + pn);
        }

        LLVMGenerator.beginStructMethod(llvmName, className, retType, paramNames, paramTypes);
        visit(ctx.block());
        LLVMGenerator.endFunction(retType);

        currentFunc = null;
    }

    @Override
    public Value visitBlock(MyLangParser.BlockContext ctx) {
        visitChildren(ctx);
        return null;
    }

    @Override
    public Value visitIfStat(MyLangParser.IfStatContext ctx) {
        int id = LLVMGenerator.labelCount++;
        List<MyLangParser.ExprContext> conds = ctx.expr();
        List<MyLangParser.BlockContext> blocks = ctx.block();
        boolean hasElse = blocks.size() > conds.size();

        String endLabel = "if_end_" + id;
        String nextLabel;

        for (int i = 0; i < conds.size(); i++) {
            Value cond = visit(conds.get(i));
            if (cond == null) return null;
            if (cond.type != VarType.BOOL) {
                semanticError(ctx, "warunek if musi byc bool");
                return null;
            }
            String thenLabel = "if_then_" + id + "_" + i;
            if (i + 1 < conds.size()) {
                nextLabel = "if_elif_" + id + "_" + (i + 1);
            } else if (hasElse) {
                nextLabel = "if_else_" + id;
            } else {
                nextLabel = endLabel;
            }
            LLVMGenerator.condBr(cond.name, thenLabel, nextLabel);
            LLVMGenerator.emitLabel(thenLabel);
            visit(blocks.get(i));
            LLVMGenerator.br(endLabel);
            if (!nextLabel.equals(endLabel)) {
                LLVMGenerator.emitLabel(nextLabel);
            }
        }

        if (hasElse) {
            visit(blocks.get(blocks.size() - 1));
            LLVMGenerator.br(endLabel);
        }

        LLVMGenerator.emitLabel(endLabel);
        return null;
    }

    @Override
    public Value visitWhileStat(MyLangParser.WhileStatContext ctx) {
        int id = LLVMGenerator.labelCount++;
        String condLabel = "while_cond_" + id;
        String bodyLabel = "while_body_" + id;
        String endLabel  = "while_end_" + id;

        LLVMGenerator.br(condLabel);
        LLVMGenerator.emitLabel(condLabel);

        Value cond = visit(ctx.expr());
        if (cond == null) return null;
        if (cond.type != VarType.BOOL) {
            semanticError(ctx, "warunek while musi byc bool");
            return null;
        }

        LLVMGenerator.condBr(cond.name, bodyLabel, endLabel);
        LLVMGenerator.emitLabel(bodyLabel);
        visit(ctx.block());
        LLVMGenerator.br(condLabel);
        LLVMGenerator.emitLabel(endLabel);
        return null;
    }

    @Override
    public Value visitForStat(MyLangParser.ForStatContext ctx) {
        String loopVar = ctx.ID().getText();
        int id = LLVMGenerator.labelCount++;
        String condLabel = "for_cond_" + id;
        String bodyLabel = "for_body_" + id;
        String endLabel  = "for_end_" + id;

        if (!variables.containsKey(loopVar)) {
            variables.put(loopVar, VarType.INT);
            llvmVarName.put(loopVar, "%" + loopVar);
            LLVMGenerator.declare(loopVar, VarType.INT);
        }

        Value startVal = visit(ctx.expr(0));
        if (startVal == null) return null;
        Value coercedStart = coerce(startVal, VarType.INT, ctx);
        if (coercedStart == null) return null;
        LLVMGenerator.assign(loopVar, coercedStart.name, VarType.INT);

        LLVMGenerator.br(condLabel);
        LLVMGenerator.emitLabel(condLabel);

        Value endVal = visit(ctx.expr(1));
        if (endVal == null) return null;
        Value coercedEnd = coerce(endVal, VarType.INT, ctx);
        if (coercedEnd == null) return null;

        String loaded = LLVMGenerator.load(loopVar, VarType.INT);
        String cmp = LLVMGenerator.icmp("slt", loaded, coercedEnd.name);

        LLVMGenerator.condBr(cmp, bodyLabel, endLabel);
        LLVMGenerator.emitLabel(bodyLabel);
        visit(ctx.block());

        if (!LLVMGenerator.blockTerminated) {
            String cur = LLVMGenerator.load(loopVar, VarType.INT);
            String inc = LLVMGenerator.add(cur, "1", VarType.INT);
            LLVMGenerator.assign(loopVar, inc, VarType.INT);
        }
        LLVMGenerator.br(condLabel);
        LLVMGenerator.emitLabel(endLabel);
        return null;
    }

    @Override
    public Value visitReturnStat(MyLangParser.ReturnStatContext ctx) {
        if (ctx.expr() == null) {
            LLVMGenerator.emitReturn(null, VarType.VOID);
            return null;
        }
        Value val = visit(ctx.expr());
        if (val == null) return null;

        if (currentFunc != null && !currentFunc.isVoid()) {
            Value coerced = coerce(val, currentFunc.returnType, ctx);
            if (coerced == null) return null;
            LLVMGenerator.emitReturn(coerced.name, currentFunc.returnType);
        } else {
            LLVMGenerator.emitReturn(val.name, val.type);
        }
        return null;
    }

    @Override
    public Value visitFuncCallStat(MyLangParser.FuncCallStatContext ctx) {
        String name = ctx.ID().getText();
        FuncInfo fi = functions.get(name);
        if (fi == null) {
            semanticError(ctx, "nieznana funkcja '" + name + "'");
            return null;
        }
        List<String> argRegs = new ArrayList<>();
        List<VarType> argTypes = new ArrayList<>();
        if (!buildArgs(ctx, ctx.argList(), fi, argRegs, argTypes)) return null;
        LLVMGenerator.callFunction(name, fi.returnType, argRegs, argTypes);
        return null;
    }

    @Override
    public Value visitFuncCallExpr(MyLangParser.FuncCallExprContext ctx) {
        String name = ctx.ID().getText();
        FuncInfo fi = functions.get(name);
        if (fi == null) {
            semanticError(ctx, "nieznana funkcja '" + name + "'");
            return null;
        }
        if (fi.isVoid()) {
            semanticError(ctx, "funkcja void '" + name + "' nie zwraca wartosci");
            return null;
        }
        List<String> argRegs = new ArrayList<>();
        List<VarType> argTypes = new ArrayList<>();
        if (!buildArgs(ctx, ctx.argList(), fi, argRegs, argTypes)) return null;
        String result = LLVMGenerator.callFunction(name, fi.returnType, argRegs, argTypes);
        return new Value(result, fi.returnType);
    }

    @Override
    public Value visitMethodCallStat(MyLangParser.MethodCallStatContext ctx) {
        String varName = ctx.ID(0).getText();
        String methodName = ctx.ID(1).getText();
        String typeName = structVars.get(varName);
        if (typeName == null) {
            semanticError(ctx, "'" + varName + "' nie jest instancja klasy");
            return null;
        }
        StructInfo si = structs.get(typeName);
        if (si == null) {
            semanticError(ctx, "nieznany typ '" + typeName + "'");
            return null;
        }
        FuncInfo mi = si.methods.get(methodName);
        if (mi == null) {
            semanticError(ctx, "nieznana metoda '" + methodName + "' w klasie '" + typeName + "'");
            return null;
        }
        List<String> argRegs = new ArrayList<>();
        List<VarType> argTypes = new ArrayList<>();
        if (!buildArgs(ctx, ctx.argList(), mi, argRegs, argTypes)) return null;
        String selfPtr = "%" + varName;
        LLVMGenerator.callMethod(mi.name, typeName, mi.returnType, selfPtr, argRegs, argTypes);
        return null;
    }

    @Override
    public Value visitMethodCallExpr(MyLangParser.MethodCallExprContext ctx) {
        String varName = ctx.ID(0).getText();
        String methodName = ctx.ID(1).getText();
        String typeName = structVars.get(varName);
        if (typeName == null) {
            semanticError(ctx, "'" + varName + "' nie jest instancja klasy");
            return null;
        }
        StructInfo si = structs.get(typeName);
        if (si == null) {
            semanticError(ctx, "nieznany typ '" + typeName + "'");
            return null;
        }
        FuncInfo mi = si.methods.get(methodName);
        if (mi == null) {
            semanticError(ctx, "nieznana metoda '" + methodName + "'");
            return null;
        }
        if (mi.isVoid()) {
            semanticError(ctx, "metoda '" + methodName + "' nie zwraca wartosci");
            return null;
        }
        List<String> argRegs = new ArrayList<>();
        List<VarType> argTypes = new ArrayList<>();
        if (!buildArgs(ctx, ctx.argList(), mi, argRegs, argTypes)) return null;
        String selfPtr = "%" + varName;
        String result = LLVMGenerator.callMethod(mi.name, typeName, mi.returnType,
                selfPtr, argRegs, argTypes);
        return new Value(result, mi.returnType);
    }

    @Override
    public Value visitFieldAssign(MyLangParser.FieldAssignContext ctx) {
        String varName = ctx.ID(0).getText();
        String fieldName = ctx.ID(1).getText();

        String typeName = structVars.get(varName);
        if (typeName == null) {
            semanticError(ctx, "'" + varName + "' nie jest struktura");
            return null;
        }
        StructInfo si = structs.get(typeName);
        if (si == null) {
            semanticError(ctx, "nieznany typ '" + typeName + "'");
            return null;
        }
        int idx = si.fieldIndex(fieldName);
        if (idx < 0) {
            semanticError(ctx, "nieznane pole '" + fieldName + "' w typie '" + typeName + "'");
            return null;
        }
        VarType ft = si.fieldTypes.get(idx);
        Value val = visit(ctx.expr());
        if (val == null) return null;
        Value coerced = coerce(val, ft, ctx);
        if (coerced == null) return null;

        String ptr = LLVMGenerator.structFieldPtr(varName, typeName, idx, ft);
        LLVMGenerator.storeToPtr(ptr, coerced.name, ft);
        return null;
    }

    @Override
    public Value visitFieldRef(MyLangParser.FieldRefContext ctx) {
        String varName = ctx.ID(0).getText();
        String fieldName = ctx.ID(1).getText();

        String typeName = structVars.get(varName);
        if (typeName == null) {
            semanticError(ctx, "'" + varName + "' nie jest struktura");
            return null;
        }
        StructInfo si = structs.get(typeName);
        if (si == null) {
            semanticError(ctx, "nieznany typ '" + typeName + "'");
            return null;
        }
        int idx = si.fieldIndex(fieldName);
        if (idx < 0) {
            semanticError(ctx, "nieznane pole '" + fieldName + "'");
            return null;
        }
        VarType ft = si.fieldTypes.get(idx);
        String ptr = LLVMGenerator.structFieldPtr(varName, typeName, idx, ft);
        String loaded = LLVMGenerator.loadFromPtr(ptr, ft);
        return new Value(loaded, ft);
    }

    @Override
    public Value visitVarDecl(MyLangParser.VarDeclContext ctx) {
        String id = ctx.ID().getText();
        VarType t = resolveTypeName(ctx.typeName());

        if (t == VarType.STRUCT) {
            String stn = ctx.typeName().getText();
            StructInfo si = structs.get(stn);
            if (si == null) {
                semanticError(ctx, "nieznany typ '" + stn + "'");
                return null;
            }
            if (isDeclared(id)) {
                semanticError(ctx, "zmienna '" + id + "' juz zostala zadeklarowana");
                return null;
            }
            structVars.put(id, stn);
            LLVMGenerator.allocaStruct(id, stn);
            return null;
        }

        if (isDeclared(id)) {
            semanticError(ctx, "zmienna '" + id + "' juz zostala zadeklarowana");
            return null;
        }
        variables.put(id, t);
        llvmVarName.put(id, "%" + id);

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
    public Value visitMatrixDecl(MyLangParser.MatrixDeclContext ctx) {
        VarType elemType = resolveTypeName(ctx.typeName());
        int rows = Integer.parseInt(ctx.INT(0).getText());
        int cols = Integer.parseInt(ctx.INT(1).getText());
        String id = ctx.ID().getText();

        if (rows <= 0 || cols <= 0) {
            semanticError(ctx, "rozmiary macierzy musza byc > 0");
            return null;
        }
        if (isDeclared(id)) {
            semanticError(ctx, "nazwa '" + id + "' juz jest zadeklarowana");
            return null;
        }

        matrices.put(id, new MatrixInfo(elemType, rows, cols));
        LLVMGenerator.declareMatrix(id, elemType, rows, cols);
        return null;
    }

    @Override
    public Value visitMatrixAssign(MyLangParser.MatrixAssignContext ctx) {
        String id = ctx.ID().getText();
        MatrixInfo info = matrices.get(id);
        if (info == null) {
            semanticError(ctx, "niezadeklarowana macierz '" + id + "'");
            return null;
        }

        Value rowVal = visit(ctx.expr(0));
        Value colVal = visit(ctx.expr(1));
        if (rowVal == null || colVal == null) return null;
        if (rowVal.type != VarType.INT || colVal.type != VarType.INT) {
            semanticError(ctx, "indeksy macierzy musza byc int");
            return null;
        }

        Value val = visit(ctx.expr(2));
        if (val == null) return null;
        Value coerced = coerce(val, info.elementType, ctx);
        if (coerced == null) return null;

        String ptr = LLVMGenerator.matrixGEP(id, rowVal.name, colVal.name,
                info.elementType, info.rows, info.cols);
        LLVMGenerator.storeToPtr(ptr, coerced.name, info.elementType);
        return null;
    }

    @Override
    public Value visitMatrixRef(MyLangParser.MatrixRefContext ctx) {
        String id = ctx.ID().getText();
        MatrixInfo info = matrices.get(id);
        if (info == null) {
            semanticError(ctx, "niezadeklarowana macierz '" + id + "'");
            return null;
        }

        Value rowVal = visit(ctx.expr(0));
        Value colVal = visit(ctx.expr(1));
        if (rowVal == null || colVal == null) return null;
        if (rowVal.type != VarType.INT || colVal.type != VarType.INT) {
            semanticError(ctx, "indeksy macierzy musza byc int");
            return null;
        }

        String ptr = LLVMGenerator.matrixGEP(id, rowVal.name, colVal.name,
                info.elementType, info.rows, info.cols);
        return new Value(LLVMGenerator.loadFromPtr(ptr, info.elementType), info.elementType);
    }

    @Override
    public Value visitReadMatrixStat(MyLangParser.ReadMatrixStatContext ctx) {
        String id = ctx.ID().getText();
        MatrixInfo info = matrices.get(id);
        if (info == null) {
            semanticError(ctx, "niezadeklarowana macierz '" + id + "'");
            return null;
        }

        Value rowVal = visit(ctx.expr(0));
        Value colVal = visit(ctx.expr(1));
        if (rowVal == null || colVal == null) return null;
        if (rowVal.type != VarType.INT || colVal.type != VarType.INT) {
            semanticError(ctx, "indeksy macierzy musza byc int");
            return null;
        }

        String ptr = LLVMGenerator.matrixGEP(id, rowVal.name, colVal.name,
                info.elementType, info.rows, info.cols);
        LLVMGenerator.scanf_into_ptr(ptr, info.elementType);
        return null;
    }

    @Override
    public Value visitArrayDecl(MyLangParser.ArrayDeclContext ctx) {
        VarType elemType = resolveTypeName(ctx.typeName());
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
        VarType elemType = resolveTypeName(ctx.typeName());
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
            if (currentClassName != null) {
                StructInfo si = structs.get(currentClassName);
                if (si != null) {
                    int idx = si.fieldIndex(id);
                    if (idx >= 0) {
                        VarType ft = si.fieldTypes.get(idx);
                        Value val = visit(ctx.expr());
                        if (val == null) return null;
                        Value coerced = coerce(val, ft, ctx);
                        if (coerced == null) return null;
                        String ptr = LLVMGenerator.structFieldPtr("self", currentClassName, idx, ft);
                        LLVMGenerator.storeToPtr(ptr, coerced.name, ft);
                        return null;
                    }
                }
            }
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

        String gep = LLVMGenerator.arrayGEP(id, adjustIndex(idxVal.name, info.startIndex),
                info.elementType, info.size);

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
        if (matrices.containsKey(id)) {
            MatrixInfo info = matrices.get(id);
            LLVMGenerator.printMatrixAll(id, info.elementType, info.rows, info.cols);
        } else if (arrays.containsKey(id)) {
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

        String gep = LLVMGenerator.arrayGEP(id, adjustIndex(idxVal.name, info.startIndex),
                info.elementType, info.size);
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

        return new Value(LLVMGenerator.phi_i1(right.name, evalEndBlock, "true", trueLabel),
                VarType.BOOL);
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

        return new Value(LLVMGenerator.phi_i1(right.name, evalEndBlock, "false", falseLabel),
                VarType.BOOL);
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
        return cast(v, resolveTypeName(ctx.typeName()), ctx);
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
            if (currentClassName != null) {
                StructInfo si = structs.get(currentClassName);
                if (si != null) {
                    int idx = si.fieldIndex(id);
                    if (idx >= 0) {
                        VarType ft = si.fieldTypes.get(idx);
                        String ptr = LLVMGenerator.structFieldPtr("self", currentClassName, idx, ft);
                        return new Value(LLVMGenerator.loadFromPtr(ptr, ft), ft);
                    }
                }
            }
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

        String gep = LLVMGenerator.arrayGEP(id, adjustIndex(idxVal.name, info.startIndex),
                info.elementType, info.size);
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

    private VarType resolveTypeName(MyLangParser.TypeNameContext ctx) {
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
                return VarType.STRUCT;
        }
    }

    private VarType resolveReturnType(MyLangParser.ReturnTypeNameContext ctx) {
        if (ctx instanceof MyLangParser.RetTypeVoidContext) return VarType.VOID;
        return resolveTypeName(((MyLangParser.RetTypeNamedContext) ctx).typeName());
    }

    private boolean buildArgs(ParserRuleContext ctx,
                              MyLangParser.ArgListContext argListCtx,
                              FuncInfo fi,
                              List<String> argRegs,
                              List<VarType> argTypes) {
        List<MyLangParser.ExprContext> exprs = new ArrayList<>();
        if (argListCtx != null) exprs = argListCtx.expr();

        if (exprs.size() != fi.paramTypes.size()) {
            semanticError(ctx, "funkcja '" + fi.name + "' oczekuje "
                    + fi.paramTypes.size() + " argumentow, podano " + exprs.size());
            return false;
        }
        for (int i = 0; i < exprs.size(); i++) {
            Value v = visit(exprs.get(i));
            if (v == null) return false;
            Value coerced = coerce(v, fi.paramTypes.get(i), ctx);
            if (coerced == null) return false;
            argRegs.add(coerced.name);
            argTypes.add(coerced.type);
        }
        return true;
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
                    ? LLVMGenerator.floatToInt(v.name) : LLVMGenerator.doubleToInt(v.name),
                    VarType.INT);
            if (v.type == VarType.BOOL)
                return new Value(LLVMGenerator.boolToInt(v.name), VarType.INT);
        }
        if (target == VarType.REAL || target == VarType.FLOAT64) {
            if (v.type == VarType.INT)
                return new Value(LLVMGenerator.intToDouble(v.name), target);
            if (v.type == VarType.FLOAT32)
                return new Value(LLVMGenerator.floatToDouble(v.name), target);
            if (v.type == VarType.REAL || v.type == VarType.FLOAT64)
                return new Value(v.name, target);
            if (v.type == VarType.BOOL)
                return new Value(LLVMGenerator.boolToFloat(v.name, target), target);
        }
        if (target == VarType.FLOAT32) {
            if (v.type == VarType.INT)
                return new Value(LLVMGenerator.intToFloat(v.name), VarType.FLOAT32);
            if (v.type == VarType.REAL || v.type == VarType.FLOAT64)
                return new Value(LLVMGenerator.doubleToFloat(v.name), VarType.FLOAT32);
            if (v.type == VarType.BOOL)
                return new Value(LLVMGenerator.boolToFloat(v.name, VarType.FLOAT32),
                        VarType.FLOAT32);
        }
        if (target == VarType.BOOL) {
            if (v.type == VarType.INT)
                return new Value(LLVMGenerator.intToBool(v.name), VarType.BOOL);
            if (v.type.isFloat())
                return new Value(LLVMGenerator.floatToBool(v.name, v.type), VarType.BOOL);
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
            return new Value[]{new Value(LLVMGenerator.intToFloat(left.name), VarType.FLOAT32),
                    right};
        if (left.type == VarType.FLOAT32 && right.type == VarType.INT)
            return new Value[]{left,
                    new Value(LLVMGenerator.intToFloat(right.name), VarType.FLOAT32)};
        if (left.type == VarType.INT &&
                (right.type == VarType.REAL || right.type == VarType.FLOAT64))
            return new Value[]{new Value(LLVMGenerator.intToDouble(left.name), right.type), right};
        if ((left.type == VarType.REAL || left.type == VarType.FLOAT64) &&
                right.type == VarType.INT)
            return new Value[]{left,
                    new Value(LLVMGenerator.intToDouble(right.name), left.type)};
        if (left.type == VarType.FLOAT32 &&
                (right.type == VarType.REAL || right.type == VarType.FLOAT64))
            return new Value[]{new Value(LLVMGenerator.floatToDouble(left.name), right.type),
                    right};
        if ((left.type == VarType.REAL || left.type == VarType.FLOAT64) &&
                right.type == VarType.FLOAT32)
            return new Value[]{left,
                    new Value(LLVMGenerator.floatToDouble(right.name), left.type)};
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
            case "==": return "eq";
            case "!=": return "ne";
            case "<":  return "slt";
            case ">":  return "sgt";
            case "<=": return "sle";
            case ">=": return "sge";
            default:   return "eq";
        }
    }

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
        return variables.containsKey(id) || arrays.containsKey(id)
                || matrices.containsKey(id) || structVars.containsKey(id);
    }

    private void semanticError(ParserRuleContext ctx, String msg) {
        errors++;
        System.err.println("Blad semantyczny, linia " + ctx.getStart().getLine() + ": " + msg);
    }

    public boolean hasErrors() {
        return errors > 0;
    }
}
