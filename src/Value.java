public class Value {
    public final String name;
    public final VarType type;

    public Value(String name, VarType type) {
        this.name = name;
        this.type = type;
    }

    @Override
    public String toString() {
        return "Value(" + name + " : " + type + ")";
    }
}
