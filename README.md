# MyLang Compiler

> Compiler for a small imperative language to LLVM IR, with arrays, matrices, structs and classes.

Kompilator prostego języka MyLang generujący kod LLVM IR.  
Zbudowany w Javie przy użyciu ANTLR4 jako generatora lexera/parsera.

> Architektura kompilatora (lexer → parser → analiza semantyczna → generowanie IR): [`ARCHITEKTURA.md`](ARCHITEKTURA.md)

---

## Szybki start

### 1. Pobierz ANTLR4 jar

```bash
wget https://www.antlr.org/download/antlr-4.13.1-complete.jar
# lub pobierz ręcznie z https://www.antlr.org/download.html
```

### 2. Zainstaluj LLVM (opcjonalnie — do uruchamiania wygenerowanego IR)

```bash
sudo apt install llvm   # Ubuntu/Debian
brew install llvm       # macOS
```

### 3. Zbuduj kompilator

```bash
make
```

### 4. Uruchom przykładowy program

```bash
make run FILE=tests/test_showcase.ml
```

### 5. Uruchom wszystkie testy

```bash
make test
```

---

## Składnia języka MyLang

### Typy danych

| Słowo kluczowe | Opis                              | Typ LLVM  |
|----------------|-----------------------------------|-----------|
| `int`          | Liczba całkowita 32-bit (signed)  | `i32`     |
| `real`         | Zmiennoprzecinkowa 64-bit         | `double`  |
| `float32`      | Zmiennoprzecinkowa 32-bit IEEE754 | `float`   |
| `float64`      | Zmiennoprzecinkowa 64-bit IEEE754 | `double`  |
| `bool`         | Wartość logiczna (true/false)     | `i1`      |
| `string`       | Ciąg znaków (max 255 znaków)      | `i8*`     |

### Deklaracja zmiennych

```
int x               // deklaracja bez inicjalizacji
int y = 5           // deklaracja z inicjalizacją
real pi = 3.14
float32 f = 1.5
float64 d = 2.71828
bool flag = true
string s = "hello"
```

### Wejście / wyjście

```
read x              // wczytaj wartość ze stdin
print x             // wypisz wartość + nowa linia
print x + y * 2     // wypisz wynik wyrażenia
```

### Arytmetyka

```
x = 2 + 3 * 4       // kolejność działań: 14
x = (2 + 3) * 4     // nawiasy: 20
x = 10 / 3          // dzielenie całkowite: 3
r = 10.0 / 3.0      // dzielenie zmiennoprzecinkowe: 3.333...
x = -y              // jednoargumentowy minus
```

### Operacje logiczne (z short-circuit dla AND i OR)

```
bool a = true
bool b = false

bool r1 = a AND b           // false (AND: jeśli lewa=false → prawa pomijana)
bool r2 = a OR b            // true  (OR:  jeśli lewa=true  → prawa pomijana)
bool r3 = a XOR b           // true  (XOR: zawsze obie strony obliczane)
bool r4 = NOT a             // false
```

### Porównania (zwracają bool)

```
bool gt  = x > y
bool lt  = x < y
bool eq  = x == y
bool ne  = x != y
bool gte = x >= y
bool lte = x <= y
```

### Rzutowanie typów

```
real r  = (real) 5          // int → real
int  i  = (int) 3.14        // real → int (obcięcie w kierunku zera)
float32 f = (float32) r     // double → float (zawężenie, możliwa utrata precyzji)
float64 d = (float64) f     // float → double (poszerzenie, bezstratne)
bool b  = (bool) x          // int → bool (0=false, inne=true)
```

### Tablice 1D o stałym rozmiarze

```
// Tablica 0-based (indeksy 0..N-1)
int[5] tab
tab[0] = 10
tab[4] = 50

// Tablica z zakresem indeksów (indeksy a..b)
int[1..5] ranged
ranged[1] = 100
ranged[5] = 500

// Dynamiczny indeks (zmienna)
int i = 2
tab[i] = tab[i] * 2

// Wypisanie wszystkich elementów
print tab

// Odczyt elementu ze stdin
read tab[0]

// Tablice innych typów
real[3] fvals
fvals[0] = 1.5
```

### Tablice 2D (macierze)

```
// Deklaracja macierzy R×C
int[3][4] mat

// Wypełnienie pętlą
for i = 0 to 3 {
    for j = 0 to 4 {
        mat[i][j] = i * 4 + j
    }
}

// Dostęp do elementu
int val = mat[1][2]

// Wypisanie całej macierzy (wszystkie elementy wiersz po wierszu)
print mat
```

### Instrukcja warunkowa

```
if (x > 0) {
    print x
} else if (x == 0) {
    print 0
} else {
    print -1
}
```

Bloki `else if` i `else` są opcjonalne. Można zagnieżdżać dowolnie głęboko.

### Pętla while

```
int i = 0
while (i < 10) {
    print i
    i = i + 1
}
```

### Pętla for

```
// Iteracja od start do end-1 (zakres prawostronnie otwarty)
for k = 1 to 11 {
    print k       // wypisuje 1..10
}

// Można używać wyrażeń jako granic
for idx = 0 to 5 {
    tab[idx] = idx * idx
}
```

### Funkcje

```
// Funkcja zwracająca wartość
fun int max(int x, int y) {
    if (x > y) {
        return x
    }
    return y
}

// Funkcja rekurencyjna
fun int fib(int n) {
    if (n <= 1) {
        return n
    }
    return fib(n - 1) + fib(n - 2)
}

// Funkcja z typem zmiennoprzecinkowym
fun real srednia(real x, real y, real z) {
    return (x + y + z) / 3.0
}

// Procedura (bez wartości zwracanej)
fun void printSep() {
    print 0
}

// Wywołanie
print max(7, 12)
print fib(8)
print srednia(3.0, 6.0, 9.0)
printSep()
```

### Struktury

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

### Klasy (struktury z metodami)

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

Wewnątrz metod pola klasy są dostępne bezpośrednio (bez `self.`).

### Komentarze

```
// komentarz jednolinijkowy
```

---

## Zaimplementowane funkcje

- Typy `int`, `real`, `float32`, `float64`, `bool`, `string` z jawnym rzutowaniem
- I/O: `read` i `print`
- Arytmetyka z poprawną kolejnością działań, operatory porównania zwracające `bool`
- Short-circuit evaluation dla `AND`/`OR` (warunkowe skoki + `phi`)
- Tablice 1D, także z dowolnym zakresem indeksów (`int[a..b]`), oraz macierze 2D
- `if / else if / else`, pętle `while` i `for`
- Funkcje z parametrami, wartością zwracaną, rekurencją i lokalnym zakresem zmiennych
- Struktury (`struct`) i klasy (`class`) z metodami
- Obsługa błędów leksykalnych, składniowych i semantycznych

---

## Polecenia Makefile

```bash
make                        # zbuduj kompilator (gen + kompilacja Java)
make run FILE=tests/test_showcase.ml  # kompiluj i uruchom program
make test                   # uruchom wszystkie pliki z tests/
make ir FILE=plik.ml        # tylko wypisz wygenerowany IR (bez uruchamiania)
make clean                  # usuń pliki budowania
```

## Pliki testowe

| Plik | Co testuje |
|------|-----------|
| `tests/test_basic.ml` | Typy podstawowe, arytmetyka, I/O |
| `tests/test_if.ml` | Instrukcja if/else if/else |
| `tests/test_while.ml` | Pętla while |
| `tests/test_for.ml` | Pętla for |
| `tests/test_functions.ml` | Funkcje, rekurencja, void |
| `tests/test_struct.ml` | Struktury i dostęp do pól |
| `tests/test_class.ml` | Klasy i metody |
| `tests/test_matrix.ml` | Macierze 2D |
| `tests/test_showcase.ml` | Wszystkie funkcjonalności języka |
