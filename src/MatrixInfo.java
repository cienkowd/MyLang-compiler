public class MatrixInfo {
    public final VarType elementType;
    public final int rows;
    public final int cols;

    public MatrixInfo(VarType elementType, int rows, int cols) {
        this.elementType = elementType;
        this.rows = rows;
        this.cols = cols;
    }

    public String llvmRowType() {
        return "[" + cols + " x " + elementType.llvm() + "]";
    }

    public String llvmMatType() {
        return "[" + rows + " x " + llvmRowType() + "]";
    }
}
