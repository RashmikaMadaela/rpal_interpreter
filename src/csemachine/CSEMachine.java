package csemachine;

import parser.ASTNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * RPAL CSE (Control-Stack-Environment) Machine.
 *
 * Execution proceeds in two phases:
 *
 * 1. Pre-processing (ST → Deltas):
 *    The Standardized Tree is converted into a numbered set of {@link Delta}
 *    objects.  Delta 0 is the top-level program; each lambda body in the ST
 *    produces a new delta.  The ST is traversed once; the result is a flat
 *    control sequence for each lambda scope.
 *
 * 2. Execution (CSE loop):
 *    The machine maintains three mutable components:
 *      - control  : Deque<Object>, processed from front (index 0 = next)
 *      - stack    : Deque<Object>, LIFO value stack
 *      - env      : current Environment frame
 *
 *    Rules applied on each control element (see {@link #step(Object)}):
 *      1.  Name  – look up in env, push value
 *      2.  Lambda marker – create Closure, push
 *      3.  Integer/String/Bool/Nil/Dummy literals – push value
 *      4.  YSTAR – push sentinel
 *      5.  Gamma + Closure  – create child env, bind param, execute delta
 *      6.  Gamma + EtaClosure – recursive self-application
 *      7.  Gamma + YSTAR  – wrap Closure in EtaClosure
 *      8.  Gamma + Builtin  – invoke built-in
 *      9.  Gamma + Tuple  – index into tuple (1-based)
 *     10.  Gamma + BuiltinPartial – finish curried built-in call
 *     11.  Beta  – conditional branch
 *     12.  Tau   – create n-tuple from top-n stack values
 *     13.  EnvMarker – pop result, restore env, push result
 *     14.  Operator (String) – binary / unary arithmetic or boolean op
 */
public class CSEMachine {

    // =========================================================================
    // Singleton values
    // =========================================================================

    /** Represents RPAL's {@code nil} (the empty tuple / empty list). */
    public static final Object NIL   = new Nil();
    /** Represents RPAL's {@code dummy} value. */
    public static final Object DUMMY = new Dummy();
    /** Sentinel pushed by the YSTAR control element. */
    private static final Object YSTAR_VALUE = new YStarValue();

    // =========================================================================
    // Control-element marker classes
    // =========================================================================

    /**
     * Lambda control element: when processed it creates a Closure pairing the
     * current environment with the body stored in {@code deltaIndex}.
     */
    public static class LambdaMarker {
        /** Index of the Delta that holds the lambda body. */
        final int deltaIndex;
        /** The parameter node (ID, ',' for tuple param, or '()' for empty). */
        final ASTNode param;

        LambdaMarker(int deltaIndex, ASTNode param) {
            this.deltaIndex = deltaIndex;
            this.param      = param;
        }
    }

    /** Marks a function-application step (gamma rule). */
    public static class GammaMarker { }

    /** Marks a conditional branch step (beta rule). */
    public static class BetaMarker {
        final int thenDelta;
        final int elseDelta;
        BetaMarker(int thenDelta, int elseDelta) {
            this.thenDelta = thenDelta;
            this.elseDelta = elseDelta;
        }
    }

    /** Marks a tuple-construction step (tau rule with arity {@code n}). */
    public static class TauMarker {
        final int n;
        TauMarker(int n) { this.n = n; }
    }

    /** Marks the Y* fixpoint combinator application. */
    private static class YStarMarker { }

    /**
     * When processed, pushes a pre-computed value onto the stack.
     * Used in eta-closure application to inject the saved argument after
     * the inner delta body has been evaluated.
     */
    private static class ValueMarker {
        final Object value;
        ValueMarker(Object value) { this.value = value; }
    }

    /** Saves the current environment for later restoration. */
    public static class EnvMarker {
        final Environment savedEnv;
        EnvMarker(Environment savedEnv) { this.savedEnv = savedEnv; }
    }

    // =========================================================================
    // Value classes
    // =========================================================================

    /** A lambda closure: environment frame + body delta + bound-variable pattern. */
    public static class Closure {
        final int   deltaIndex;
        final ASTNode param;       // the parameter pattern
        final Environment env;    // the environment at closure creation

        Closure(int deltaIndex, ASTNode param, Environment env) {
            this.deltaIndex = deltaIndex;
            this.param      = param;
            this.env        = env;
        }

        @Override
        public String toString() {
            return "<closure:" + deltaIndex + ">";
        }
    }

    /**
     * An eta-closure produced by applying YSTAR to a lambda closure.
     * Used to implement RPAL recursion.
     */
    public static class EtaClosure {
        final Closure inner;

        EtaClosure(Closure inner) { this.inner = inner; }

        @Override
        public String toString() {
            return "<eta:" + inner.deltaIndex + ">";
        }
    }

    /**
     * A partially-applied built-in function (needed for multi-argument built-ins
     * such as {@code Conc}).  Stores the first argument; the second application
     * completes the computation.
     */
    public static class BuiltinPartial {
        final String name;
        final Object firstArg;

        BuiltinPartial(String name, Object firstArg) {
            this.name     = name;
            this.firstArg = firstArg;
        }
    }

    /** Singleton representation of RPAL {@code nil}. */
    private static class Nil {
        @Override public String toString() { return "nil"; }
        @Override public boolean equals(Object o) { return o instanceof Nil; }
        @Override public int hashCode() { return 0; }
    }

    /** Singleton representation of RPAL {@code dummy}. */
    private static class Dummy {
        @Override public String toString() { return "dummy"; }
    }

    /** Sentinel pushed onto the stack when YSTAR is encountered on the control. */
    private static class YStarValue {
        @Override public String toString() { return "<YSTAR>"; }
    }

    // =========================================================================
    // Machine state
    // =========================================================================

    private final List<Delta> deltas = new ArrayList<>();
    private final Deque<Object> control = new ArrayDeque<>();
    private final Deque<Object> stack   = new ArrayDeque<>();
    private Environment currentEnv;
    private static final GammaMarker GAMMA = new GammaMarker();

    // =========================================================================
    // Public interface
    // =========================================================================

    /**
     * Executes the RPAL Standardized Tree {@code st} and returns the value
     * left on the stack (or {@code DUMMY} if the stack is empty).
     *
     * @param st the root of the ST produced by {@link standardizer.Standardizer}
     * @return the computed RPAL value
     */
    public Object execute(ASTNode st) {
        // Phase 1: pre-process ST → deltas
        deltas.add(new Delta(0));  // delta[0] = top-level program
        preprocess(st, 0);

        // Phase 2: run the CSE loop
        currentEnv = new Environment();
        loadDelta(0);

        while (!control.isEmpty()) {
            step(control.pollFirst());
        }

        return stack.isEmpty() ? DUMMY : stack.peekFirst();
    }

    // =========================================================================
    // Phase 1 – Pre-processing (ST → Deltas)
    // =========================================================================

    /**
     * Recursively flattens the sub-tree rooted at {@code node} into the delta
     * identified by {@code deltaIdx}.
     *
     * Processing order within a delta mirrors the evaluation order of the CSE
     * machine: for {@code gamma(F, A)}, rator F is added before rand A, and
     * the {@code gamma} marker comes last.
     */
    private void preprocess(ASTNode node, int deltaIdx) {
        Delta d = deltas.get(deltaIdx);
        String type = node.getType();

        switch (type) {
            // -- Literals and names: push directly as leaf ASTNodes
            case "ID":    case "INT":   case "STR":
            case "true":  case "false":
            case "nil":   case "dummy":
                d.add(node);
                break;

            // -- Y* combinator sentinel
            case "YSTAR":
                d.add(new YStarMarker());
                break;

            // -- Lambda: create a new delta for the body
            case "lambda": {
                int bodyIdx = deltas.size();
                deltas.add(new Delta(bodyIdx));
                preprocess(node.child(1), bodyIdx);     // child 1 = body
                d.add(new LambdaMarker(bodyIdx, node.child(0))); // child 0 = param
                break;
            }

            // -- Function application
            case "gamma":
                preprocess(node.child(0), deltaIdx);    // rator (evaluated first)
                preprocess(node.child(1), deltaIdx);    // rand  (evaluated second)
                d.add(GAMMA);
                break;

            // -- Conditional: body deltas for then/else
            case "->": {
                int thenIdx = deltas.size();
                deltas.add(new Delta(thenIdx));
                preprocess(node.child(1), thenIdx);

                int elseIdx = deltas.size();
                deltas.add(new Delta(elseIdx));
                preprocess(node.child(2), elseIdx);

                preprocess(node.child(0), deltaIdx);   // condition
                d.add(new BetaMarker(thenIdx, elseIdx));
                break;
            }

            // -- Tuple construction
            case "tau":
                for (ASTNode child : node.getChildren()) {
                    preprocess(child, deltaIdx);
                }
                d.add(new TauMarker(node.childCount()));
                break;

            // -- Binary operators: left, right, then operator string
            case "+": case "-": case "*": case "/": case "**":
            case "or": case "&":
            case "gr": case "ge": case "ls": case "le":
            case "eq": case "ne":
            case "aug":
                preprocess(node.child(0), deltaIdx);
                preprocess(node.child(1), deltaIdx);
                d.add(type);
                break;

            // -- Unary operators
            case "neg": case "not":
                preprocess(node.child(0), deltaIdx);
                d.add(type);
                break;

            // -- Infix application: A @ f B  ≡  (f A) B
            case "@":
                preprocess(node.child(1), deltaIdx);   // f
                preprocess(node.child(0), deltaIdx);   // A
                d.add(GAMMA);                          // apply f to A
                preprocess(node.child(2), deltaIdx);   // B
                d.add(GAMMA);                          // apply result to B
                break;

            default:
                throw new RuntimeException(
                    "CSEMachine: unexpected ST node type '" + type + "' during pre-processing");
        }
    }

    // =========================================================================
    // Phase 2 – CSE execution loop
    // =========================================================================

    /** Dispatches a single control element to the appropriate handler. */
    private void step(Object elem) {
        if (elem instanceof ASTNode)      { processLiteral((ASTNode) elem); }
        else if (elem instanceof LambdaMarker) { processLambda((LambdaMarker) elem); }
        else if (elem instanceof GammaMarker)  { processGamma(); }
        else if (elem instanceof BetaMarker)   { processBeta((BetaMarker) elem); }
        else if (elem instanceof TauMarker)    { processTau((TauMarker) elem); }
        else if (elem instanceof EnvMarker)    { processEnvMarker((EnvMarker) elem); }
        else if (elem instanceof YStarMarker)  { stack.push(YSTAR_VALUE); }
        else if (elem instanceof ValueMarker)  { stack.push(((ValueMarker) elem).value); }
        else if (elem instanceof String)       { processOperator((String) elem); }
        else {
            throw new RuntimeException("CSEMachine: unknown control element " + elem);
        }
    }

    // -- Rule 1/3: literal and name nodes
    private void processLiteral(ASTNode node) {
        switch (node.getType()) {
            case "ID":    stack.push(currentEnv.lookup(node.getValue())); break;
            case "INT":   stack.push(Integer.parseInt(node.getValue()));   break;
            case "STR":   stack.push(node.getValue());                     break;
            case "true":  stack.push(Boolean.TRUE);                        break;
            case "false": stack.push(Boolean.FALSE);                       break;
            case "nil":   stack.push(NIL);                                 break;
            case "dummy": stack.push(DUMMY);                               break;
        }
    }

    // -- Rule 2: lambda → closure
    private void processLambda(LambdaMarker lm) {
        stack.push(new Closure(lm.deltaIndex, lm.param, currentEnv));
    }

    // -- Rules 5–10: function application
    private void processGamma() {
        Object rand  = stack.pop();   // argument (evaluated last, on top)
        Object rator = stack.pop();   // function (evaluated first)

        if (rator instanceof Closure) {
            applyClosure((Closure) rator, rand);

        } else if (rator instanceof EtaClosure) {
            applyEtaClosure((EtaClosure) rator, rand);

        } else if (rator instanceof YStarValue) {
            // Rule 7: YSTAR + lambda → eta-closure
            if (!(rand instanceof Closure)) {
                throw new RuntimeException(
                    "CSEMachine: YSTAR expects a Closure argument, got " + rand);
            }
            stack.push(new EtaClosure((Closure) rand));

        } else if (rator instanceof BuiltInFunctions.Builtin) {
            applyBuiltin(((BuiltInFunctions.Builtin) rator).name, rand);

        } else if (rator instanceof BuiltinPartial) {
            applyBuiltinPartial((BuiltinPartial) rator, rand);

        } else if (rator instanceof Tuple) {
            // Tuple selection: tuple @ index (1-based)
            if (!(rand instanceof Integer)) {
                throw new RuntimeException(
                    "CSEMachine: tuple index must be INTEGER, got " + rand);
            }
            stack.push(((Tuple) rator).get((Integer) rand));

        } else {
            throw new RuntimeException(
                "CSEMachine: cannot apply " + rator + " to " + rand);
        }
    }

    /**
     * Applies a lambda closure to a value.
     *
     * Creates a new environment extending the closure's captured environment,
     * binds the parameter, then loads the closure's delta body onto the control
     * preceded by an {@link EnvMarker} that will restore the previous env.
     */
    private void applyClosure(Closure closure, Object rand) {
        Environment savedEnv = currentEnv;
        currentEnv = closure.env.extend();
        bindParam(closure.param, rand, currentEnv);

        // Control: [delta_body..., envMarker, ...rest...]
        // We prepend envMarker first so it comes after the delta body
        control.addFirst(new EnvMarker(savedEnv));
        loadDeltaToFront(closure.deltaIndex);
    }

    /**
     * Applies an eta-closure to a value.
     *
     * Creates an environment where the recursive name is bound to the eta
     * closure itself (enabling self-reference), evaluates the inner delta
     * body (which produces a lambda), then that lambda is applied to rand.
     *
     * To achieve this the machine:
     *   1. saves rand back onto the stack
     *   2. loads the inner delta body (which will push a Closure onto the stack)
     *   3. arranges a GammaMarker to apply that Closure to rand
     */
    private void applyEtaClosure(EtaClosure eta, Object rand) {
        Environment savedEnv = currentEnv;
        currentEnv = eta.inner.env.extend();
        // Bind the recursive variable to the eta-closure itself
        bindParam(eta.inner.param, eta, currentEnv);

        // Desired processing order:
        //   delta[inner]    -> pushes a new Closure (rator) onto the stack
        //   EnvMarker       -> pops that Closure, restores env, pushes it back
        //   ValueMarker(rand) -> pushes the saved argument (rand) on top of Closure
        //   GammaMarker     -> pops rand (top), pops Closure, applies Closure(rand)
        control.addFirst(GAMMA);
        control.addFirst(new ValueMarker(rand));
        control.addFirst(new EnvMarker(savedEnv));
        loadDeltaToFront(eta.inner.deltaIndex);
    }

    // -- Rule 11: conditional branch
    private void processBeta(BetaMarker beta) {
        Object cond = stack.pop();
        if (!(cond instanceof Boolean)) {
            throw new RuntimeException(
                "CSEMachine: conditional requires a boolean value, got " + valueToString(cond));
        }
        loadDeltaToFront((Boolean) cond ? beta.thenDelta : beta.elseDelta);
    }

    // -- Rule 12: tuple constructor
    private void processTau(TauMarker tau) {
        List<Object> elems = new ArrayList<>(tau.n);
        for (int i = 0; i < tau.n; i++) {
            elems.add(stack.pop());
        }
        // Elements were pushed E1 first … En last, so they come off En … E1;
        // reverse to restore original order (E1, E2, …, En).
        Collections.reverse(elems);
        stack.push(new Tuple(elems));
    }

    // -- Rule 13: environment restore
    private void processEnvMarker(EnvMarker marker) {
        Object result = stack.pop();   // result of lambda body
        currentEnv = marker.savedEnv;
        stack.push(result);
    }

    // -- Rule 14: arithmetic / boolean / relational operators
    private void processOperator(String op) {
        switch (op) {
            // Unary
            case "neg": {
                int a = popInt("neg");
                stack.push(-a);
                break;
            }
            case "not": {
                boolean a = popBool("not");
                stack.push(!a);
                break;
            }
            // Binary arithmetic
            case "+":  { int b = popInt("+");  int a = popInt("+");  stack.push(a + b); break; }
            case "-":  { int b = popInt("-");  int a = popInt("-");  stack.push(a - b); break; }
            case "*":  { int b = popInt("*");  int a = popInt("*");  stack.push(a * b); break; }
            case "/":  { int b = popInt("/");  int a = popInt("/");  stack.push(a / b); break; }
            case "**": {
                int b = popInt("**"); int a = popInt("**");
                stack.push((int) Math.pow(a, b));
                break;
            }
            // Boolean
            case "or":  { boolean b = popBool("or");  boolean a = popBool("or");  stack.push(a || b); break; }
            case "&":   { boolean b = popBool("&");   boolean a = popBool("&");   stack.push(a && b); break; }
            // Relational – support both Integer and String comparisons
            case "gr": { Object b = stack.pop(); Object a = stack.pop(); stack.push(compare(a, b) >  0); break; }
            case "ge": { Object b = stack.pop(); Object a = stack.pop(); stack.push(compare(a, b) >= 0); break; }
            case "ls": { Object b = stack.pop(); Object a = stack.pop(); stack.push(compare(a, b) <  0); break; }
            case "le": { Object b = stack.pop(); Object a = stack.pop(); stack.push(compare(a, b) <= 0); break; }
            case "eq": { Object b = stack.pop(); Object a = stack.pop(); stack.push(rpalEquals(a, b));   break; }
            case "ne": { Object b = stack.pop(); Object a = stack.pop(); stack.push(!rpalEquals(a, b));  break; }
            // Augmentation: append element to tuple
            case "aug": {
                Object b = stack.pop();  // element to append
                Object a = stack.pop();  // base tuple (or nil)
                if (a == NIL) {
                    stack.push(new Tuple(java.util.Arrays.asList(b)));
                } else if (a instanceof Tuple) {
                    stack.push(((Tuple) a).augment(b));
                } else {
                    throw new RuntimeException(
                        "CSEMachine: 'aug' expects tuple or nil on left, got " + a);
                }
                break;
            }
            default:
                throw new RuntimeException("CSEMachine: unknown operator '" + op + "'");
        }
    }

    // =========================================================================
    // Built-in function application
    // =========================================================================

    private void applyBuiltin(String name, Object arg) {
        switch (name) {

            case "Print":
            case "Printnl":
                System.out.println(valueToString(arg));
                stack.push(DUMMY);
                break;

            case "ItoS":
                stack.push(String.valueOf(popIntFrom("ItoS", arg)));
                break;

            case "Order":
                if (arg == NIL)               stack.push(0);
                else if (arg instanceof Tuple) stack.push(((Tuple) arg).size());
                else throw new RuntimeException("CSEMachine: Order expects tuple, got " + arg);
                break;

            case "Null":
                stack.push(arg == NIL || (arg instanceof Tuple && ((Tuple) arg).size() == 0));
                break;

            case "Isinteger":    stack.push(arg instanceof Integer);   break;
            case "Isstring":     stack.push(arg instanceof String);    break;
            case "Istuple":      stack.push(arg instanceof Tuple || arg == NIL); break;
            case "Isfunction":
                stack.push(arg instanceof Closure
                    || arg instanceof EtaClosure
                    || arg instanceof BuiltInFunctions.Builtin
                    || arg instanceof BuiltinPartial);
                break;
            case "Isdummy":      stack.push(arg == DUMMY);             break;
            case "Istruthvalue": stack.push(arg instanceof Boolean);   break;

            case "Stem": {
                String s = (String) arg;
                if (s.isEmpty()) throw new RuntimeException("CSEMachine: Stem of empty string");
                stack.push(String.valueOf(s.charAt(0)));
                break;
            }
            case "Stern": {
                String s = (String) arg;
                if (s.isEmpty()) throw new RuntimeException("CSEMachine: Stern of empty string");
                stack.push(s.substring(1));
                break;
            }

            // Conc is curried: first call stores the first argument
            case "Conc":
                stack.push(new BuiltinPartial("Conc", arg));
                break;

            case "Abs": stack.push(Math.abs(popIntFrom("Abs", arg))); break;
            case "Neg": stack.push(-popIntFrom("Neg", arg));          break;

            default:
                throw new RuntimeException("CSEMachine: unknown built-in '" + name + "'");
        }
    }

    /** Completes the application of a curried built-in. */
    private void applyBuiltinPartial(BuiltinPartial partial, Object arg) {
        switch (partial.name) {
            case "Conc":
                stack.push((String) partial.firstArg + (String) arg);
                break;
            default:
                throw new RuntimeException(
                    "CSEMachine: unknown partial built-in '" + partial.name + "'");
        }
    }

    // =========================================================================
    // Parameter binding
    // =========================================================================

    /**
     * Binds a value to a parameter pattern in the given environment.
     *
     *   ID node     – simple binding: name = value
     *   ',' node    – tuple destructuring: each child ID = element[i]
     *   '()' node   – empty tuple parameter: no binding required
     */
    private void bindParam(ASTNode param, Object value, Environment env) {
        switch (param.getType()) {
            case "ID":
                env.bind(param.getValue(), value);
                break;

            case ",": {
                // Tuple destructuring
                if (!(value instanceof Tuple)) {
                    throw new RuntimeException(
                        "CSEMachine: tuple-parameter binding expected Tuple, got " + value);
                }
                Tuple t = (Tuple) value;
                List<ASTNode> ids = param.getChildren();
                if (ids.size() != t.size()) {
                    throw new RuntimeException(
                        "CSEMachine: tuple size mismatch – expected " + ids.size()
                        + " elements, got " + t.size());
                }
                for (int i = 0; i < ids.size(); i++) {
                    env.bind(ids.get(i).getValue(), t.get(i + 1));
                }
                break;
            }

            case "()":
                // Empty-tuple parameter: value must be nil (or empty tuple)
                break;

            default:
                throw new RuntimeException(
                    "CSEMachine: unexpected parameter node type '" + param.getType() + "'");
        }
    }

    // =========================================================================
    // Control loading helpers
    // =========================================================================

    /** Appends all elements of the given delta to the BACK of the control. */
    private void loadDelta(int deltaIdx) {
        for (Object elem : deltas.get(deltaIdx).getControls()) {
            control.addLast(elem);
        }
    }

    /** Prepends all elements of the given delta to the FRONT of the control
     *  so they are processed before whatever is already queued. */
    private void loadDeltaToFront(int deltaIdx) {
        List<Object> elems = deltas.get(deltaIdx).getControls();
        for (int i = elems.size() - 1; i >= 0; i--) {
            control.addFirst(elems.get(i));
        }
    }

    // =========================================================================
    // Value utilities
    // =========================================================================

    /**
     * Converts an RPAL runtime value to its human-readable output string.
     * This is the format used by the {@code Print} built-in.
     */
    public static String valueToString(Object value) {
        if (value instanceof Integer)   return value.toString();
        if (value instanceof String)    return (String) value;
        if (value instanceof Boolean)   return value.toString();
        if (value == NIL)               return "nil";
        if (value == DUMMY)             return "dummy";
        if (value instanceof Tuple) {
            Tuple t = (Tuple) value;
            if (t.size() == 0) return "nil";
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < t.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(valueToString(t.get(i + 1)));
            }
            sb.append(")");
            return sb.toString();
        }
        if (value instanceof Closure)     return value.toString();
        if (value instanceof EtaClosure)  return value.toString();
        if (value instanceof BuiltInFunctions.Builtin) return value.toString();
        return String.valueOf(value);
    }

    /** Compares two RPAL values (Integer or String) for ordering. */
    private int compare(Object a, Object b) {
        if (a instanceof Integer && b instanceof Integer) {
            return Integer.compare((Integer) a, (Integer) b);
        }
        if (a instanceof String && b instanceof String) {
            return ((String) a).compareTo((String) b);
        }
        throw new RuntimeException(
            "CSEMachine: cannot compare " + a + " and " + b);
    }

    /** RPAL equality: integers, booleans, strings, nil, and dummy are compared by value. */
    private boolean rpalEquals(Object a, Object b) {
        if (a == NIL   && b == NIL)   return true;
        if (a == DUMMY && b == DUMMY) return true;
        return Objects.equals(a, b);
    }

    // =========================================================================
    // Stack-pop helpers with type checking
    // =========================================================================

    private int popInt(String op) {
        Object v = stack.pop();
        if (!(v instanceof Integer)) {
            throw new RuntimeException(
                "CSEMachine: operator '" + op + "' expects INTEGER, got " + v);
        }
        return (Integer) v;
    }

    private boolean popBool(String op) {
        Object v = stack.pop();
        if (!(v instanceof Boolean)) {
            throw new RuntimeException(
                "CSEMachine: operator '" + op + "' expects BOOLEAN, got " + v);
        }
        return (Boolean) v;
    }

    private int popIntFrom(String op, Object v) {
        if (!(v instanceof Integer)) {
            throw new RuntimeException(
                "CSEMachine: built-in '" + op + "' expects INTEGER, got " + v);
        }
        return (Integer) v;
    }
}
