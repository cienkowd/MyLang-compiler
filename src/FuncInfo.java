import java.util.List;

public class FuncInfo {
    public final String name;
    public final VarType returnType;
    public final List<String> paramNames;
    public final List<VarType> paramTypes;

    public FuncInfo(String name, VarType returnType,
                    List<String> paramNames, List<VarType> paramTypes) {
        this.name = name;
        this.returnType = returnType;
        this.paramNames = paramNames;
        this.paramTypes = paramTypes;
    }

    public boolean isVoid() {
        return returnType == null || returnType == VarType.VOID;
    }
}
