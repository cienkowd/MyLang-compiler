fun int add(int a, int b) {
    return a + b
}

fun int factorial(int n) {
    if (n <= 1) {
        return 1
    }
    return n * factorial(n - 1)
}

fun void printLine(int x) {
    print x
}

int result = add(3, 4)
print result

int f5 = factorial(5)
print f5

printLine(42)
