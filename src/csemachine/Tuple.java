package csemachine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable RPAL tuple value.
 *
 * Tuples are created by the {@code tau} constructor in the CSE Machine and by
 * the {@code aug} operator.  They are 1-indexed as required by RPAL built-ins
 * such as {@code Order} and tuple-selection ({@code gamma(tuple, n)}).
 *
 * The empty tuple is the representation of RPAL's {@code nil}.
 */
public class Tuple {

    private final List<Object> elements;

    /** Creates a tuple with a defensive copy of the supplied element list. */
    public Tuple(List<Object> elements) {
        this.elements = Collections.unmodifiableList(new ArrayList<>(elements));
    }

    /** Number of elements in this tuple. */
    public int size() {
        return elements.size();
    }

    /**
     * Returns the element at the given 1-based index.
     *
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    public Object get(int index) {
        return elements.get(index - 1);
    }

    /** Returns the underlying element list (unmodifiable). */
    public List<Object> getElements() {
        return elements;
    }

    /**
     * Returns a new tuple that is this tuple augmented with {@code value}
     * appended at the end.
     */
    public Tuple augment(Object value) {
        List<Object> newElems = new ArrayList<>(elements);
        newElems.add(value);
        return new Tuple(newElems);
    }

    /** Renders the tuple in RPAL output format: {@code (e1, e2, e3)}. */
    @Override
    public String toString() {
        // The actual rendering of element values is delegated to CSEMachine
        // via the static helper, so we expose the element list and let the
        // machine build the string.  This default is used only for debugging.
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(elements.get(i));
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Tuple)) return false;
        return elements.equals(((Tuple) obj).elements);
    }

    @Override
    public int hashCode() {
        return elements.hashCode();
    }
}
