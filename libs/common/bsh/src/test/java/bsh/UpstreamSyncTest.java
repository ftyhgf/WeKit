package bsh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UpstreamSyncTest {
    @Test
    void arrayBracketsOnTheTypeAndParameterNameAreCombined() throws Exception {
        Interpreter interpreter = new Interpreter();
        assertEquals(7, interpreter.eval(
                "int first(int[] values[]) { return values[0][0]; }"
                        + "first(new int[][] { { 7 } });"));
        assertEquals(int[][].class, interpreter.getNameSpace()
                .getMethod("first", new Class<?>[] { int[][].class }).getParameterTypes()[0]);
    }

    @Test
    void realMethodsWinOverPropertyAliasesAndAliasesRemainCallable() throws Exception {
        Interpreter interpreter = new Interpreter();
        interpreter.set("bean", new PropertyMethods());
        assertEquals("method", interpreter.eval("bean.name();"));
        assertEquals("getter", interpreter.eval("bean.name;"));
        assertEquals(true, interpreter.eval("bean.up();"));
        assertEquals(6, interpreter.eval("bean.up(5);"));
    }

    @Test
    void floatOverflowAndMixedIntegralArithmeticKeepJavaFloatSemantics() throws Exception {
        Interpreter interpreter = new Interpreter();
        Object overflow = interpreter.eval("Float.MAX_VALUE * 2.0f;");
        assertEquals(Float.POSITIVE_INFINITY, assertInstanceOf(Float.class, overflow));
        Object rounded = interpreter.eval("16777216.0f + 1;");
        assertEquals(16777216.0f, assertInstanceOf(Float.class, rounded));
    }

    @Test
    void boxedUnaryOperatorsPreservePrefixPostfixAndAssignedValues() throws Exception {
        Interpreter interpreter = new Interpreter();
        assertEquals(-7, interpreter.eval("Integer boxed = Integer.valueOf(7); -boxed;"));
        assertEquals(7, interpreter.eval("boxed++;"));
        assertEquals(9, interpreter.eval("++boxed;"));
        assertEquals(9, interpreter.get("boxed"));
        assertEquals(Integer.class, interpreter.eval("boxed.getClass();"));
    }

    @Test
    void printCommandUsesTheInterpreterOutput() throws Exception {
        Interpreter interpreter = new Interpreter();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        interpreter.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
        interpreter.eval("print(\"upstream\");");
        assertEquals("upstream" + System.lineSeparator(), output.toString(StandardCharsets.UTF_8));
    }

    public static class PropertyMethods {
        public String name() { return "method"; }
        public String getName() { return "getter"; }
        public int up(int value) { return value + 1; }
        public boolean isUp() { return true; }
    }
}
