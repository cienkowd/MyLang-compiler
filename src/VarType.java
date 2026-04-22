public enum VarType {
    INT, REAL, FLOAT32, FLOAT64, BOOL, STRING, UNKNOWN;

    public String llvm() {
        switch (this) {
            case INT:
                return "i32";
            case REAL:
            case FLOAT64:
                return "double";
            case FLOAT32:
                return "float";
            case BOOL:
                return "i1";
            case STRING:
                return "i8*";
            default:
                return "void";
        }
    }

    public boolean isFloat() {
        return this == REAL || this == FLOAT32 || this == FLOAT64;
    }

    public boolean isNumeric() {
        return this == INT || isFloat();
    }

    @Override
    public String toString() {
        switch (this) {
            case INT:
                return "int";
            case REAL:
                return "real";
            case FLOAT32:
                return "float32";
            case FLOAT64:
                return "float64";
            case BOOL:
                return "bool";
            case STRING:
                return "string";
            default:
                return "unknown";
        }
    }
}
