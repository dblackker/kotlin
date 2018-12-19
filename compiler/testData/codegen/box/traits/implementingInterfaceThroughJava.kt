// TARGET_BACKEND: JVM

// FILE: J.java
public abstract class J implements I<String> {}

// FILE: main.kt

interface I<T> {
    fun foo(x: T): T = x
}

class K : J()

fun box(): String {
    return K().foo("OK")
}

