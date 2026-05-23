package csemachine;

import java.util.ArrayList;
import java.util.List;

/**
 * A pre-processed, flat control sequence for a single lambda body (or the
 * top-level program).
 *
 * The CSE Machine converts the Standardized Tree (ST) into a set of numbered
 * {@code Delta} objects before beginning execution.  Delta 0 is always the
 * top-level control.  Each {@code lambda} node in the ST produces a new Delta
 * for its body.
 *
 * Elements stored in a Delta are:
 *   - {@link parser.ASTNode}     – leaf literals and identifier references
 *   - {@link CSEMachine.LambdaMarker}  – lambda closure constructor
 *   - {@link CSEMachine.GammaMarker}   – function application
 *   - {@link CSEMachine.BetaMarker}    – conditional branch
 *   - {@link CSEMachine.TauMarker}     – tuple constructor
 *   - {@link CSEMachine.YStarMarker}   – Y* fixpoint combinator
 *   - {@link String}            – binary / unary operator name
 */
public class Delta {

    private final int index;
    private final List<Object> controls;

    /**
     * Creates an empty Delta with the given index.
     *
     * @param index the delta's index (0 = top-level program)
     */
    public Delta(int index) {
        this.index = index;
        this.controls = new ArrayList<>();
    }

    /** Appends a control element to this Delta. */
    public void add(Object element) {
        controls.add(element);
    }

    /** Returns the ordered list of control elements. */
    public List<Object> getControls() {
        return controls;
    }

    /** Returns the index assigned to this Delta. */
    public int getIndex() {
        return index;
    }
}
