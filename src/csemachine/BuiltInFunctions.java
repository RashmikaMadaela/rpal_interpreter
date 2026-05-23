package csemachine;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Registry of all RPAL built-in function names.
 *
 * Each entry in {@link #NAMES} is pre-loaded into the root environment as a
 * {@link Builtin} marker object so that it can be distinguished from ordinary
 * string values on the CSE Machine stack.
 */
public class BuiltInFunctions {

    /** The set of all recognised RPAL built-in names. */
    public static final Set<String> NAMES = new HashSet<>(Arrays.asList(
        "Print", "Printnl",
        "ItoS",
        "Order", "Null",
        "Isinteger", "Isstring", "Istuple", "Isfunction", "Isdummy", "Istruthvalue",
        "Stem", "Stern", "Conc",
        "Abs", "Neg"
    ));

    /**
     * A value placed on the CSE Machine stack to represent an unapplied
     * built-in function.  Using a dedicated class avoids confusing built-in
     * names with ordinary RPAL string values (both would otherwise be
     * {@code java.lang.String}).
     */
    public static class Builtin {
        public final String name;

        public Builtin(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return "<builtin:" + name + ">";
        }
    }

    private BuiltInFunctions() { /* utility class – no instances */ }
}
