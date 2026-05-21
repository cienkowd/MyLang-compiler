public class Value {
    public final String name;
    public final VarType type;
    public final String structTypeName;

    public Value(String name, VarType type) {
        this(name, type, null);
    }

    public Value(String name, VarType type, String structTypeName) {
        this.name = name;
        this.type = type;
        this.structTypeName = structTypeName;
    }

    @Override
    public String toString() {
        if (structTypeName != null) return "Value(" + name + " : " + structTypeName + ")";
        return "Value(" + name + " : " + type + ")";
    }
}
