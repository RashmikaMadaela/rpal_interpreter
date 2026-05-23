package csemachine;

import java.util.HashMap;
import java.util.Map;

/**
 * A single environment frame in the RPAL CSE Machine.
 *
 * Environments form a singly-linked chain (child → parent). Name lookup
 * walks the chain until the name is found or the root (e0) is reached.
 *
 * The root environment (constructed with no parent) is pre-loaded with
 * all RPAL built-in function bindings.
 */
public class Environment {

    private final Map<String, Object> bindings = new HashMap<>();
    private final Environment parent;

    /**
     * Creates the root environment (e0) with all built-in function bindings.
     * Each built-in is stored as a {@link BuiltInFunctions.Builtin} marker.
     */
    public Environment() {
        this.parent = null;
        for (String name : BuiltInFunctions.NAMES) {
            bindings.put(name, new BuiltInFunctions.Builtin(name));
        }
    }

    /**
     * Creates a child environment that extends {@code parent}.
     * Initially empty; bindings are added via {@link #bind(String, Object)}.
     */
    public Environment(Environment parent) {
        this.parent = parent;
    }

    /**
     * Looks up {@code name} in this environment and all ancestors.
     *
     * @throws RuntimeException if the name is unbound in any frame
     */
    public Object lookup(String name) {
        if (bindings.containsKey(name)) {
            return bindings.get(name);
        }
        if (parent != null) {
            return parent.lookup(name);
        }
        throw new RuntimeException("CSE Machine: unbound name '" + name + "'");
    }

    /** Adds (or overwrites) a binding in this frame only. */
    public void bind(String name, Object value) {
        bindings.put(name, value);
    }

    /** Creates a fresh child environment whose parent is this frame. */
    public Environment extend() {
        return new Environment(this);
    }
}
