public class ArrayInfo {
    public final VarType elementType;
    public final int startIndex;
    public final int endIndex;
    public final int size;

    public ArrayInfo(VarType elementType, int size) {
        this(elementType, 0, size - 1);
    }

    public ArrayInfo(VarType elementType, int startIndex, int endIndex) {
        this.elementType = elementType;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        this.size = endIndex - startIndex + 1;
    }
}
