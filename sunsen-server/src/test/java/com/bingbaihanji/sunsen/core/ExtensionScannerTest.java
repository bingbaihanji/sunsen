package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.PluginLoadException;
import com.bingbaihanji.sunsen.api.annotation.Extension;
import com.bingbaihanji.sunsen.api.annotation.ExtensionPoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionScannerTest {

    @ExtensionPoint
    interface Greeting {
        String say();
    }

    @ExtensionPoint(allowMultiple = false)
    interface SingleUse {
        String use();
    }

    @ExtensionPoint(allowMultiple = true)
    interface MultiUse {
        String use();
    }

    interface NotAnExtensionPoint {
        void foo();
    }

    static class MixedInterface implements Greeting, NotAnExtensionPoint {
        public String say() { return "hi"; }
        public void foo() {}
    }

    static abstract class AbstractGreeting implements Greeting {}

    @Extension
    static class ConcreteGreeting extends AbstractGreeting {
        public String say() { return "hello"; }
    }

    static class PureNotExtension implements NotAnExtensionPoint {
        public void foo() {}
    }

    @Extension(id = "a", order = 0)
    static class SingleA implements SingleUse { public String use() { return "a"; } }

    @Extension(id = "b", order = 1)
    static class SingleB implements SingleUse { public String use() { return "b"; } }

    @Extension
    static class MultiA implements MultiUse { public String use() { return "a"; } }

    @Extension
    static class MultiB implements MultiUse { public String use() { return "b"; } }

    @Test
    void inferExtensionPointTypesDirectInterface() {
        List<Class<?>> types = ExtensionScanner.inferExtensionPointTypes(MixedInterface.class);
        assertTrue(types.contains(Greeting.class));
        assertFalse(types.contains(NotAnExtensionPoint.class));
    }

    @Test
    void inferExtensionPointTypesThroughAbstractParent() {
        List<Class<?>> types = ExtensionScanner.inferExtensionPointTypes(ConcreteGreeting.class);
        assertTrue(types.contains(Greeting.class));
    }

    @Test
    void inferExtensionPointTypesOnlyNonExtensionPoint() {
        List<Class<?>> types = ExtensionScanner.inferExtensionPointTypes(PureNotExtension.class);
        assertTrue(types.isEmpty());
    }

    @Test
    void validateSingleExtensionsAllowMultipleFalseTwoImplsThrows() {
        Map<Class<?>, List<Class<?>>> map = Map.of(
                SingleUse.class, List.of(SingleA.class, SingleB.class)
        );
        ExtensionScanner scanner = new ExtensionScanner(null);
        assertThrows(PluginLoadException.class, () -> scanner.validateSingleExtensions(map));
    }

    @Test
    void validateSingleExtensionsAllowMultipleTrueAllowsMultiple() {
        Map<Class<?>, List<Class<?>>> map = Map.of(
                MultiUse.class, List.of(MultiA.class, MultiB.class)
        );
        ExtensionScanner scanner = new ExtensionScanner(null);
        assertDoesNotThrow(() -> scanner.validateSingleExtensions(map));
    }

    @Test
    void validateSingleExtensionsAllowMultipleFalseOneImplPasses() {
        Map<Class<?>, List<Class<?>>> map = Map.of(
                SingleUse.class, List.of(SingleA.class)
        );
        ExtensionScanner scanner = new ExtensionScanner(null);
        assertDoesNotThrow(() -> scanner.validateSingleExtensions(map));
    }
}
