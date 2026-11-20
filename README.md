# MyLang Compiler

> Compiler for a small imperative language to LLVM IR, with arrays, matrices, structs and classes.

MyLang is a statically typed imperative language. The compiler is written in Java, uses ANTLR4 to generate the lexer and parser, and emits textual LLVM IR that can be run with `lli` or compiled to a native binary with `clang`.

Compiler architecture (lexer → parser → semantic analysis → IR generation): [`ARCHITECTURE.md`](ARCHITECTURE.md)

---

## Quick start

### 1. Download the ANTLR4 jar

```bash
wget https://www.antlr.org/download/antlr-4.13.1-complete.jar
# or download it manually from https://www.antlr.org/download.html
```

### 2. Install LLVM (optional, needed to run the generated IR)

```bash
sudo apt install llvm   # Ubuntu/Debian
brew install llvm       # macOS
```

### 3. Build the compiler

```bash
make
```

### 4. Run an example program

```bash
make run FILE=tests/test_showcase.ml
```

### 5. Run all tests

```bash
make test
```

---

## Language syntax

### Data types

| Keyword   | Description                        | LLVM type |
|-----------|------------------------------------|-----------|
| `int`     | 32-bit signed integer              | `i32`     |
| `real`    | 64-bit floating point              | `double`  |
| `float32` | 32-bit IEEE 754 floating point     | `float`   |
| `float64` | 64-bit IEEE 754 floating point     | `double`  |
| `bool`    | Boolean (true/false)               | `i1`      |
| `string`  | Character string (max 255 chars)   | `i8*`     |

### Variable declarations

```
int x               // declaration without initialization
int y = 5           // declaration with initialization
real pi = 3.14
float32 f = 1.5
float64 d = 2.71828
bool flag = true
string s = "hello"
```

### Input / output

```
read x              // read a value from stdin
print x             // print a value followed by a newline
print x + y * 2     // print the result of an expression
```

### Arithmetic

```
x = 2 + 3 * 4       // operator precedence: 14
x = (2 + 3) * 4     // parentheses: 20
x = 10 / 3          // integer division: 3
r = 10.0 / 3.0      // floating-point division: 3.333...
x = -y              // unary minus
```

### Logical operators (short-circuit AND and OR)

```
bool a = true
bool b = false

bool r1 = a AND b           // false (AND: if left is false, right is skipped)
bool r2 = a OR b            // true  (OR:  if left is true, right is skipped)
bool r3 = a XOR b           // true  (XOR: both sides are always evaluated)
bool r4 = NOT a             // false
```

### Comparisons (return bool)

```
bool gt  = x > y
bool lt  = x < y
bool eq  = x == y
bool ne  = x != y
bool gte = x >= y
bool lte = x <= y
```

### Type casts

```
real r  = (real) 5          // int → real
int  i  = (int) 3.14        // real → int (truncation toward zero)
float32 f = (float32) r     // double → float (narrowing, may lose precision)
float64 d = (float64) f     // float → double (widening, lossless)
bool b  = (bool) x          // int → bool (0 = false, anything else = true)
```

### Fixed-size 1D arrays

```
// 0-based array (indices 0..N-1)
int[5] tab
tab[0] = 10
tab[4] = 50

// Array with a custom index range (indices a..b)
int[1..5] ranged
ranged[1] = 100
ranged[5] = 500

// Dynamic index (variable)
int i = 2
tab[i] = tab[i] * 2

// Print all elements
print tab

// Read an element from stdin
read tab[0]

// Arrays of other types
real[3] fvals
fvals[0] = 1.5
```

### 2D arrays (matrices)

```
// Declare an R×C matrix
int[3][4] mat

// Fill it with a loop
for i = 0 to 3 {
    for j = 0 to 4 {
        mat[i][j] = i * 4 + j
    }
}

// Element access
int val = mat[1][2]

// Print the whole matrix (all elements, row by row)
print mat
```

### Conditionals

```
if (x > 0) {
    print x
} else if (x == 0) {
    print 0
} else {
    print -1
}
```

`else if` and `else` blocks are optional and can be nested to any depth.

### While loop

```
int i = 0
while (i < 10) {
    print i
    i = i + 1
}
```

### For loop

```
// Iterates from start to end-1 (half-open range)
for k = 1 to 11 {
    print k       // prints 1..10
}

// Bounds can be expressions
for idx = 0 to 5 {
    tab[idx] = idx * idx
}
```

### Functions

```
// Function returning a value
fun int max(int x, int y) {
    if (x > y) {
        return x
    }
    return y
}

// Recursive function
fun int fib(int n) {
    if (n <= 1) {
        return n
    }
    return fib(n - 1) + fib(n - 2)
}

// Floating-point function
fun real average(real x, real y, real z) {
    return (x + y + z) / 3.0
}

// Procedure (no return value)
fun void printSep() {
    print 0
}

// Calls
print max(7, 12)
print fib(8)
print average(3.0, 6.0, 9.0)
printSep()
```

### Structs

```
struct Vec2 {
    real x
    real y
}

Vec2 v
v.x = 3.0
v.y = 4.0
real len_sq = v.x * v.x + v.y * v.y
print len_sq
```

### Classes (structs with methods)

```
class Stack {
    int val0
    int val1
    int val2
    int top

    fun void init() {
        top = 0
    }

    fun void push(int v) {
        if (top == 0) { val0 = v }
        if (top == 1) { val1 = v }
        if (top == 2) { val2 = v }
        top = top + 1
    }

    fun int peek() {
        if (top == 3) { return val2 }
        if (top == 2) { return val1 }
        return val0
    }

    fun int size() {
        return top
    }
}

Stack stk
stk.init()
stk.push(10)
stk.push(20)
stk.push(30)
print stk.size()   // 3
print stk.peek()   // 30
```

Inside methods, class fields are accessed directly (no `self.`).

### Comments

```
// single-line comment
```

---

## Implemented features

- Types `int`, `real`, `float32`, `float64`, `bool`, `string` with explicit casts
- I/O: `read` and `print`
- Arithmetic with correct operator precedence, comparison operators returning `bool`
- Short-circuit evaluation of `AND`/`OR` (conditional branches + `phi`)
- 1D arrays, including custom index ranges (`int[a..b]`), and 2D matrices
- `if / else if / else`, `while` and `for` loops
- Functions with parameters, return values, recursion and local scope
- Structs (`struct`) and classes (`class`) with methods
- Lexical, syntax and semantic error reporting

---

## Makefile targets

```bash
make                                  # build the compiler (ANTLR generation + Java compilation)
make run FILE=tests/test_showcase.ml  # compile and run a program
make test                             # run every file in tests/
make ir FILE=file.ml                  # print the generated IR only (no execution)
make clean                            # remove build output
```

## Test programs

| File | What it covers |
|------|----------------|
| `tests/test_basic.ml` | Basic types, arithmetic, I/O |
| `tests/test_bool.ml` | `bool`, short-circuit AND/OR/XOR |
| `tests/test_comparisons.ml` | Comparison operators |
| `tests/test_float_precision.ml` | `float32` vs `float64` precision |
| `tests/test_string.ml` | `string` type |
| `tests/test_arrays.ml` | 0-based and ranged arrays |
| `tests/test_if.ml` | if / else if / else |
| `tests/test_while.ml` | while loop |
| `tests/test_for.ml` | for loop |
| `tests/test_functions.ml` | Functions, recursion, void |
| `tests/test_struct.ml` | Structs and field access |
| `tests/test_class.ml` | Classes and methods |
| `tests/test_matrix.ml` | 2D matrices |
| `tests/test_errors.ml` | Semantic errors |
| `tests/test_showcase.ml` | All language features |

---

Built as coursework for the compilers course, Applied Computer Science, Warsaw University of Technology.
