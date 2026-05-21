import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StructInfo {
    public final String name;
    public final List<String> fieldNames;
    public final List<VarType> fieldTypes;
    public final boolean isClass;
    public final Map<String, FuncInfo> methods;

    public StructInfo(String name, List<String> fieldNames,
                      List<VarType> fieldTypes, boolean isClass) {
        this.name = name;
        this.fieldNames = fieldNames;
        this.fieldTypes = fieldTypes;
        this.isClass = isClass;
        this.methods = new LinkedHashMap<>();
    }

    public int fieldIndex(String fieldName) {
        return fieldNames.indexOf(fieldName);
    }

    public VarType fieldType(String fieldName) {
        int idx = fieldIndex(fieldName);
        return idx >= 0 ? fieldTypes.get(idx) : null;
    }

    public String llvmTypeName() {
        return "%" + name;
    }
}
