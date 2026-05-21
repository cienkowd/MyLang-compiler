int    a = 10
int    b = 3
real   pi = 3.14159
float32 f32 = 1.5
float64 f64 = 2.718281828
bool   flag = true
string msg = "MyLang"

print a
print pi
print f32
print f64
print flag
print msg

int sum  = a + b * 2
int diff = a - b
int prod = a * b
int quot = a / b
print sum
print diff
print prod
print quot

real ar = (real) a
int  ri = (int) pi
print ar
print ri

bool t = true
bool f = false
bool r_and = t AND f
bool r_or  = t OR  f
bool r_xor = t XOR f
bool r_not = NOT t
print r_and
print r_or
print r_xor
print r_not

bool sc = false AND t
print sc

bool eq  = a == b
bool neq = a != b
bool lt  = a < 100
bool ge  = a >= 10
print eq
print neq
print lt
print ge

if (a > b) {
    print a
} else if (a == b) {
    print b
} else {
    print 0
}

int i = 1
int silnia = 1
while (i <= 5) {
    silnia = silnia * i
    i = i + 1
}
print silnia

int s = 0
for k = 1 to 11 {
    s = s + k
}
print s

int[5] tab
for idx = 0 to 5 {
    tab[idx] = idx * idx
}
print tab

int[1..4] ranged
ranged[1] = 10
ranged[2] = 20
ranged[3] = 30
ranged[4] = 40
print ranged

int[3][3] mat
for row = 0 to 3 {
    for col = 0 to 3 {
        mat[row][col] = row * 3 + col + 1
    }
}
print mat

int diag = mat[0][0] + mat[1][1] + mat[2][2]
print diag

fun int max(int x, int y) {
    if (x > y) {
        return x
    }
    return y
}

fun int fib(int n) {
    if (n <= 1) {
        return n
    }
    return fib(n - 1) + fib(n - 2)
}

fun real srednia(real x, real y, real z) {
    return (x + y + z) / 3.0
}

fun void printSep() {
    print 0
}

print max(7, 12)
print fib(8)
print srednia(3.0, 6.0, 9.0)
printSep()

struct Vec2 {
    real x
    real y
}

Vec2 v
v.x = 3.0
v.y = 4.0
real len_sq = v.x * v.x + v.y * v.y
print len_sq

struct Point {
    int px
    int py
}

Point p1
p1.px = 1
p1.py = 2
int manhattan = p1.px + p1.py
print manhattan

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
print stk.size()
print stk.peek()

fun int matTrace() {
    int[3][3] lm
    lm[0][0] = 1
    lm[0][1] = 0
    lm[0][2] = 0
    lm[1][0] = 0
    lm[1][1] = 2
    lm[1][2] = 0
    lm[2][0] = 0
    lm[2][1] = 0
    lm[2][2] = 3
    return lm[0][0] + lm[1][1] + lm[2][2]
}

print matTrace()
