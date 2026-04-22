public class LLVMGenerator {

    static String header_text = "";
    static String main_text = "";
    static int reg = 1;
    static int labelCount = 0;
    static int readBufCount = 0;
    static int strLitCount = 0;
    static String currentBlock = "entry";

    static String nextReg() {
        return "%" + reg++;
    }

    static String newLabel(String prefix) {
        return prefix + "_" + (labelCount++);
    }

    static void declare(String id, VarType t) {
        main_text += "  %" + id + " = alloca " + t.llvm() + "\n";
    }

    static void declareString(String id) {
        main_text += "  %" + id + " = alloca i8*\n";
        main_text += "  %" + id + "_buf = alloca [256 x i8]\n";
        String ptr = nextReg();
        main_text += "  " + ptr + " = getelementptr inbounds [256 x i8], "
                + "[256 x i8]* %" + id + "_buf, i32 0, i32 0\n";
        main_text += "  store i8* " + ptr + ", i8** %" + id + "\n";
    }

    static void declareArray(String id, VarType elemType, int size) {
        main_text += "  %" + id + " = alloca [" + size + " x " + elemType.llvm() + "]\n";
    }

    static void assign(String id, String val, VarType t) {
        main_text += "  store " + t.llvm() + " " + val + ", " + t.llvm() + "* %" + id + "\n";
    }

    static String load(String id, VarType t) {
        String r = nextReg();
        main_text += "  " + r + " = load " + t.llvm() + ", " + t.llvm() + "* %" + id + "\n";
        return r;
    }

    static String loadString(String id) {
        String r = nextReg();
        main_text += "  " + r + " = load i8*, i8** %" + id + "\n";
        return r;
    }

    static String arrayGEP(String id, String idxReg, VarType elemType, int size) {
        String r = nextReg();
        String T = elemType.llvm();
        main_text += "  " + r + " = getelementptr inbounds [" + size + " x " + T + "], ["
                + size + " x " + T + "]* %" + id + ", i32 0, i32 " + idxReg + "\n";
        return r;
    }

    static String loadFromPtr(String ptr, VarType elemType) {
        String r = nextReg();
        main_text += "  " + r + " = load " + elemType.llvm() + ", "
                + elemType.llvm() + "* " + ptr + "\n";
        return r;
    }

    static void storeToPtr(String ptr, String val, VarType elemType) {
        main_text += "  store " + elemType.llvm() + " " + val + ", "
                + elemType.llvm() + "* " + ptr + "\n";
    }

    static String add(String v1, String v2, VarType t) {
        String r = nextReg();
        main_text += (t == VarType.INT)
                ? "  " + r + " = add i32 " + v1 + ", " + v2 + "\n"
                : "  " + r + " = fadd " + t.llvm() + " " + v1 + ", " + v2 + "\n";
        return r;
    }

    static String sub(String v1, String v2, VarType t) {
        String r = nextReg();
        main_text += (t == VarType.INT)
                ? "  " + r + " = sub i32 " + v1 + ", " + v2 + "\n"
                : "  " + r + " = fsub " + t.llvm() + " " + v1 + ", " + v2 + "\n";
        return r;
    }

    static String mul(String v1, String v2, VarType t) {
        String r = nextReg();
        main_text += (t == VarType.INT)
                ? "  " + r + " = mul i32 " + v1 + ", " + v2 + "\n"
                : "  " + r + " = fmul " + t.llvm() + " " + v1 + ", " + v2 + "\n";
        return r;
    }

    static String div(String v1, String v2, VarType t) {
        String r = nextReg();
        main_text += (t == VarType.INT)
                ? "  " + r + " = sdiv i32 " + v1 + ", " + v2 + "\n"
                : "  " + r + " = fdiv " + t.llvm() + " " + v1 + ", " + v2 + "\n";
        return r;
    }

    static String neg(String v, VarType t) {
        String r = nextReg();
        main_text += (t == VarType.INT)
                ? "  " + r + " = sub i32 0, " + v + "\n"
                : "  " + r + " = fneg " + t.llvm() + " " + v + "\n";
        return r;
    }

    static String xor_i1(String v1, String v2) {
        String r = nextReg();
        main_text += "  " + r + " = xor i1 " + v1 + ", " + v2 + "\n";
        return r;
    }

    static String not_i1(String v) {
        String r = nextReg();
        main_text += "  " + r + " = xor i1 " + v + ", true\n";
        return r;
    }

    static String icmp(String op, String v1, String v2) {
        String r = nextReg();
        main_text += "  " + r + " = icmp " + op + " i32 " + v1 + ", " + v2 + "\n";
        return r;
    }

    static String fcmp(String op, String v1, String v2, VarType t) {
        String r = nextReg();
        main_text += "  " + r + " = fcmp " + op + " " + t.llvm() + " " + v1 + ", " + v2 + "\n";
        return r;
    }

    static void condBr(String cond, String trueLabel, String falseLabel) {
        main_text += "  br i1 " + cond + ", label %" + trueLabel + ", label %" + falseLabel + "\n";
    }

    static void br(String label) {
        main_text += "  br label %" + label + "\n";
    }

    static void emitLabel(String label) {
        main_text += label + ":\n";
        currentBlock = label;
    }

    static String phi_i1(String v1, String l1, String v2, String l2) {
        String r = nextReg();
        main_text += "  " + r + " = phi i1 [" + v1 + ", %" + l1 + "], [" + v2 + ", %" + l2 + "]\n";
        return r;
    }

    static String intToDouble(String v) {
        String r = nextReg();
        main_text += "  " + r + " = sitofp i32 " + v + " to double\n";
        return r;
    }

    static String intToFloat(String v) {
        String r = nextReg();
        main_text += "  " + r + " = sitofp i32 " + v + " to float\n";
        return r;
    }

    static String doubleToInt(String v) {
        String r = nextReg();
        main_text += "  " + r + " = fptosi double " + v + " to i32\n";
        return r;
    }

    static String floatToInt(String v) {
        String r = nextReg();
        main_text += "  " + r + " = fptosi float " + v + " to i32\n";
        return r;
    }

    static String floatToDouble(String v) {
        String r = nextReg();
        main_text += "  " + r + " = fpext float " + v + " to double\n";
        return r;
    }

    static String doubleToFloat(String v) {
        String r = nextReg();
        main_text += "  " + r + " = fptrunc double " + v + " to float\n";
        return r;
    }

    static String intToBool(String v) {
        String r = nextReg();
        main_text += "  " + r + " = icmp ne i32 " + v + ", 0\n";
        return r;
    }

    static String floatToBool(String v, VarType t) {
        String r = nextReg();
        main_text += "  " + r + " = fcmp une " + t.llvm() + " " + v + ", 0.0\n";
        return r;
    }

    static String boolToInt(String v) {
        String r = nextReg();
        main_text += "  " + r + " = zext i1 " + v + " to i32\n";
        return r;
    }

    static String boolToFloat(String v, VarType target) {
        String asInt = boolToInt(v);
        return (target == VarType.FLOAT32) ? intToFloat(asInt) : intToDouble(asInt);
    }

    static void printf_i32(String val) {
        String r = nextReg();
        main_text += "  " + r + " = call i32 (i8*, ...) @printf("
                + "i8* getelementptr inbounds ([4 x i8], [4 x i8]* @fmt_i32_p, i32 0, i32 0), "
                + "i32 " + val + ")\n";
    }

    static void printf_double(String val) {
        String r = nextReg();
        main_text += "  " + r + " = call i32 (i8*, ...) @printf("
                + "i8* getelementptr inbounds ([4 x i8], [4 x i8]* @fmt_f64_p, i32 0, i32 0), "
                + "double " + val + ")\n";
    }

    static void printf_float(String val) {
        String ext = floatToDouble(val);
        String r = nextReg();
        main_text += "  " + r + " = call i32 (i8*, ...) @printf("
                + "i8* getelementptr inbounds ([4 x i8], [4 x i8]* @fmt_f64_p, i32 0, i32 0), "
                + "double " + ext + ")\n";
    }

    static void printf_bool(String val) {
        printf_i32(boolToInt(val));
    }

    static void printf_string(String ptr) {
        String r = nextReg();
        main_text += "  " + r + " = call i32 (i8*, ...) @printf("
                + "i8* getelementptr inbounds ([4 x i8], [4 x i8]* @fmt_str_p, i32 0, i32 0), "
                + "i8* " + ptr + ")\n";
    }

    static void scanf_i32(String id) {
        String r = nextReg();
        main_text += "  " + r + " = call i32 (i8*, ...) @__isoc99_scanf("
                + "i8* getelementptr inbounds ([3 x i8], [3 x i8]* @fmt_i32_r, i32 0, i32 0), "
                + "i32* %" + id + ")\n";
    }

    static void scanf_double(String id) {
        String r = nextReg();
        main_text += "  " + r + " = call i32 (i8*, ...) @__isoc99_scanf("
                + "i8* getelementptr inbounds ([4 x i8], [4 x i8]* @fmt_f64_r, i32 0, i32 0), "
                + "double* %" + id + ")\n";
    }

    static void scanf_float(String id) {
        String r = nextReg();
        main_text += "  " + r + " = call i32 (i8*, ...) @__isoc99_scanf("
                + "i8* getelementptr inbounds ([3 x i8], [3 x i8]* @fmt_f32_r, i32 0, i32 0), "
                + "float* %" + id + ")\n";
    }

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

    static void scanf_string(String id) {
        String ptr = nextReg();
        main_text += "  " + ptr + " = load i8*, i8** %" + id + "\n";
        String r = nextReg();
        main_text += "  " + r + " = call i32 (i8*, ...) @__isoc99_scanf("
                + "i8* getelementptr inbounds ([6 x i8], [6 x i8]* @fmt_str_r, i32 0, i32 0), "
                + "i8* " + ptr + ")\n";
    }

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

    static void printArrayAll(String id, VarType elemType, int size) {
        String T = elemType.llvm();
        for (int i = 0; i < size; i++) {
            String gep = nextReg();
            main_text += "  " + gep + " = getelementptr inbounds [" + size + " x " + T + "], ["
                    + size + " x " + T + "]* %" + id + ", i32 0, i32 " + i + "\n";
            String val = nextReg();
            main_text += "  " + val + " = load " + T + ", " + T + "* " + gep + "\n";
            switch (elemType) {
                case INT:
                    printf_i32(val);
                    break;
                case REAL:
                case FLOAT64:
                    printf_double(val);
                    break;
                case FLOAT32:
                    printf_float(val);
                    break;
                case BOOL:
                    printf_bool(val);
                    break;
                default:
                    break;
            }
        }
    }

    static String stringLiteralPtr(String content) {
        int id = ++strLitCount;
        String gname = "@strlit_" + id;
        int len = content.length() + 1;
        String escaped = content.replace("\\", "\\5C").replace("\"", "\\22");
        header_text += gname + " = constant [" + len + " x i8] c\"" + escaped + "\\00\"\n";
        String r = nextReg();
        main_text += "  " + r + " = getelementptr inbounds [" + len + " x i8], ["
                + len + " x i8]* " + gname + ", i32 0, i32 0\n";
        return r;
    }

    static String generate() {
        StringBuilder sb = new StringBuilder();
        sb.append("declare i32 @printf(i8*, ...)\n");
        sb.append("declare i32 @__isoc99_scanf(i8*, ...)\n");
        sb.append("declare i8* @strcpy(i8*, i8*)\n");
        sb.append("\n");
        sb.append("@fmt_i32_p = constant [4 x i8] c\"%d\\0A\\00\"\n");
        sb.append("@fmt_i32_r = constant [3 x i8] c\"%d\\00\"\n");
        sb.append("@fmt_f64_p = constant [4 x i8] c\"%f\\0A\\00\"\n");
        sb.append("@fmt_f64_r = constant [4 x i8] c\"%lf\\00\"\n");
        sb.append("@fmt_f32_r = constant [3 x i8] c\"%f\\00\"\n");
        sb.append("@fmt_str_p = constant [4 x i8] c\"%s\\0A\\00\"\n");
        sb.append("@fmt_str_r = constant [6 x i8] c\"%255s\\00\"\n");
        sb.append("\n");
        if (!header_text.isEmpty()) {
            sb.append(header_text);
            sb.append("\n");
        }
        sb.append("define i32 @main() nounwind {\n");
        sb.append(main_text);
        sb.append("  ret i32 0\n");
        sb.append("}\n");
        return sb.toString();
    }
}
