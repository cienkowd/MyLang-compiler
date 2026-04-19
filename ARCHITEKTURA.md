# Architektura kompilatora MyLang

## Spis treści
1. [Ogólny schemat kompilacji](#1-ogólny-schemat-kompilacji)
2. [Struktura plików](#2-struktura-plików)
3. [Etap 1 — Analiza leksykalna (Lexer)](#3-etap-1--analiza-leksykalna-lexer)
4. [Etap 2 — Parsowanie i AST (Parser)](#4-etap-2--parsowanie-i-ast-parser)
5. [Etap 3 — Analiza semantyczna (LLVMActions)](#5-etap-3--analiza-semantyczna-llvmactions)
6. [Etap 4 — Generowanie kodu IR (LLVMGenerator)](#6-etap-4--generowanie-kodu-ir-llvmgenerator)
7. [System typów i konwersje](#7-system-typów-i-konwersje)
8. [Short-circuit evaluation](#8-short-circuit-evaluation)
9. [Tablice z zakresami indeksów](#9-tablice-z-zakresami-indeksów)
10. [Obsługa błędów](#10-obsługa-błędów)
11. [Kompletny przykład kompilacji](#11-kompletny-przykład-kompilacji)

---

## 1. Ogólny schemat kompilacji

```
Plik źródłowy (.ml)
        │
        ▼
┌───────────────┐
│  CharStream   │  ← wczytanie pliku jako strumień znaków
└───────────────┘
        │
        ▼
┌───────────────┐
│ MyLangLexer   │  ← tokenizacja: znaki → tokeny (INT, ID, 'print', ...)
│   (ANTLR4)    │    błędy leksykalne → MyLangErrorListener
└───────────────┘
        │ CommonTokenStream
        ▼
┌───────────────┐
│ MyLangParser  │  ← parsowanie: tokeny → drzewo składniowe (AST)
│   (ANTLR4)    │    błędy składniowe → MyLangErrorListener
└───────────────┘
        │ ParseTree
        ▼
┌───────────────┐
│  LLVMActions  │  ← visitor: przechodzi AST, sprawdza typy, buduje IR
│  (visitor)    │    błędy semantyczne → stderr
└───────────────┘
        │ LLVMGenerator.main_text / header_text
        ▼
┌───────────────┐
│LLVMGenerator  │  ← składa kompletny moduł LLVM IR jako tekst
│  .generate()  │
└───────────────┘
        │
        ▼
  stdout: LLVM IR (.ll)
        │
        ▼  (poza kompilatorem)
  lli / clang → wykonanie
```

---

## 2. Struktura plików

```
kompilator/
├── src/
│   ├── MyLang.g4          Gramatyka ANTLR4 – definiuje składnię języka
│   ├── VarType.java        Enum typów MyLang z mapowaniem na LLVM IR
│   ├── Value.java          Para (rejestr LLVM, typ) – wynik ewaluacji wyrażenia
│   ├── ArrayInfo.java      Metadane tablicy: typ elementów, zakres, rozmiar
│   ├── LLVMGenerator.java  Generator kodu LLVM IR – metody emitujące instrukcje
│   ├── LLVMActions.java    Visitor semantyczny – logika kompilacji
│   └── Main.java           Punkt wejścia + MyLangErrorListener
│
├── src/gen/                (generowane przez ANTLR – nie edytować ręcznie)
│   ├── MyLangLexer.java
│   ├── MyLangParser.java
│   ├── MyLangBaseVisitor.java
│   └── MyLangVisitor.java
│
├── build/                  (skompilowane .class – nie commitować)
│
├── tests/
│   ├── test_basic.ml           int/real, arytmetyka, I/O
│   ├── test_bool.ml            bool, short-circuit AND/OR/XOR
│   ├── test_comparisons.ml     operatory porównania zwracające bool
│   ├── test_float_precision.ml float32 vs float64 – różnice precyzji
│   ├── test_string.ml          typ string, print, read
│   ├── test_arrays.ml          tablice 0-based i z zakresem indeksów
│   └── test_errors.ml          błędy semantyczne (zakomentowane przykłady)
│
├── Makefile                Automatyczna budowa i uruchamianie
├── README.md               Szybki start i składnia języka
└── ARCHITEKTURA.md         Ten dokument
```

---

## 3. Etap 1 — Analiza leksykalna (Lexer)

ANTLR4 generuje `MyLangLexer` na podstawie definicji tokenów w `MyLang.g4`.
Lekser stosuje zasadę **maximal munch** — zawsze próbuje dopasować jak najdłuższy token.

### Ważne reguły tokenizacji

| Token       | Wzorzec               | Przykład    | Uwaga                                      |
|-------------|----------------------|-------------|--------------------------------------------|
| `DOTDOT`    | `'..'`               | `1..5`      | Musi być przed `REAL` — inaczej `1.` = REAL |
| `REAL`      | `[0-9]+ '.' [0-9]+`  | `3.14`      | Wymaga cyfr po kropce (od drugiej wersji)  |
| `INT`       | `[0-9]+`             | `42`        | Po `REAL` — munch wybierze REAL dla `3.14` |
| `EQ`        | `'=='`               | `==`        | Przed `'='` — inaczej `==` = dwa `=`       |
| `LE`        | `'<='`               | `<=`        | Przed `'<'`                                |
| `WS`        | `[ \t]+`             | (spacje)    | Pominięte (skip)                           |
| `NEWLINE`   | `'\r'? '\n'`         | koniec linii| NIE pominięty — używany jako separator     |

### Przykład tokenizacji

```
int[1..5] arr
```
Tokeny: `ID("int")`, `'['`, `INT(1)`, `DOTDOT(..)`, `INT(5)`, `']'`, `ID(arr)`, `NEWLINE`

---

## 4. Etap 2 — Parsowanie i AST (Parser)

Parser buduje **drzewo składniowe (AST)** zgodne z gramatyką `MyLang.g4`.
Hierarchia reguł odzwierciedla pierwszeństwo operatorów.

### Hierarchia wyrażeń (od najniższego do najwyższego priorytetu)

```
expr
  └─ orExpr          (OR)
       └─ xorExpr    (XOR)
            └─ andExpr    (AND)
                 └─ notExpr    (NOT)
                      └─ cmpExpr    (==, !=, <, >, <=, >=)
                           └─ addExpr    (+, -)
                                └─ mulExpr    (*, /)
                                     └─ castExpr    ( (typ) )
                                          └─ unaryExpr    (-)
                                               └─ primaryExpr    (literały, ID, arr[i])
```

Przykład: `a + b * 2 > 5 AND flag`

```
andExpr
  ├─ cmpExpr (>)
  │    ├─ addExpr (+)
  │    │    ├─ mulExpr → a
  │    │    └─ mulExpr (*)
  │    │         ├─ castExpr → b
  │    │         └─ castExpr → 2
  │    └─ addExpr → 5
  └─ notExpr → flag
```

### Nazwane alternatywy ANTLR4

Składnia `#nazwaAlternatywy` w gramatyce powoduje, że ANTLR generuje
osobną metodę visitXxx dla każdej etykiety:

```antlr
addExpr
    : addExpr '+' mulExpr  #addOp   → visitAddOp(AddOpContext ctx)
    | addExpr '-' mulExpr  #subOp   → visitSubOp(SubOpContext ctx)
    | mulExpr               #addPass → visitAddPass(AddPassContext ctx)
    ;
```

Dzięki temu `LLVMActions` może obsłużyć każdy operator niezależnie,
bez sprawdzania `ctx.op.getType()`.

---

## 5. Etap 3 — Analiza semantyczna (LLVMActions)

`LLVMActions` rozszerza `MyLangBaseVisitor<Value>` i implementuje logikę
semantyczną + generowanie kodu dla każdego węzła AST.

### Tablica symboli

```java
Map<String, VarType>   variables;  // name → type dla skalarów
Map<String, ArrayInfo> arrays;     // name → ArrayInfo dla tablic
```

Przy deklaracji `int x = 5`:
1. Sprawdź czy `x` nie jest już zadeklarowane
2. Dodaj do `variables`: `{"x" → INT}`
3. Wywołaj `LLVMGenerator.declare("x", INT)` → emituje `%x = alloca i32`
4. Odwiedź wyrażenie `5` → `Value("5", INT)`
5. Wywołaj `LLVMGenerator.assign("x", "5", INT)` → emituje `store i32 5, i32* %x`

### Wzorzec ewaluacji wyrażeń

Każdy visitor wyrażenia zwraca `Value(rejestr, typ)`:

```
visit(addExpr: a + b)
  → Value leftVal  = visit(a)    → Value("%1", INT)
  → Value rightVal = visit(b)    → Value("%2", INT)
  → unifyNumeric(left, right)    → bez konwersji (oba INT)
  → LLVMGenerator.add("%1","%2", INT) → emituje "%3 = add i32 %1, %2"
  → return Value("%3", INT)
```

### Unifikacja typów (unifyNumeric)

Przy operacjach binarnych słabszy typ jest promowany do szerszego:

```
(INT, FLOAT32)   → (FLOAT32, FLOAT32)    sitofp i32 → float
(INT, REAL)      → (REAL, REAL)          sitofp i32 → double
(FLOAT32, REAL)  → (REAL, REAL)          fpext float → double
(REAL, FLOAT64)  → (REAL, REAL)          oba to double – bez konwersji
```

---

## 6. Etap 4 — Generowanie kodu IR (LLVMGenerator)

`LLVMGenerator` gromadzi kod w dwóch buforach tekstowych i składa je
w kompletny moduł LLVM IR przez `generate()`.

### Budowanie IR

```
header_text += "@strlit_1 = constant [6 x i8] c\"hello\00\"\n"
main_text   += "  %1 = alloca i32\n"
main_text   += "  store i32 5, i32* %1\n"
...
generate() → łączy wszystkie części w jeden plik .ll
```

### Numeracja rejestrów (SSA)

LLVM wymaga, by każda instrukcja produkująca wartość miała unikalny numer:

```llvm
%1 = alloca i32
%2 = load i32, i32* %1
%3 = add i32 %2, 5
%4 = call i32 @printf(...)
```

Rejestr nie może być pominięty — nawet wyniki `call` zwracające wartość,
która nie jest używana, MUSZĄ dostać numer. Stąd wzorzec:

```java
String r = nextReg();
main_text += "  " + r + " = call i32 @printf(...)\n";
```

### Format strings

Globalne stałe reużywalne przez wszystkie operacje I/O:

| Stała          | Wartość        | Użycie                |
|----------------|---------------|----------------------|
| `@fmt_i32_p`   | `"%d\n\0"`    | printf int           |
| `@fmt_f64_p`   | `"%f\n\0"`    | printf double/float  |
| `@fmt_str_p`   | `"%s\n\0"`    | printf string        |
| `@fmt_i32_r`   | `"%d\0"`      | scanf int/bool       |
| `@fmt_f64_r`   | `"%lf\0"`     | scanf double         |
| `@fmt_f32_r`   | `"%f\0"`      | scanf float          |
| `@fmt_str_r`   | `"%255s\0"`   | scanf string (255 max)|

---

## 7. System typów i konwersje

### Mapowanie typów

```
MyLang   │ LLVM IR │ Rozmiar │ Uwaga
─────────┼─────────┼─────────┼──────────────────────────────
int      │ i32     │ 32 bit  │ signed, dzielenie = sdiv
real     │ double  │ 64 bit  │ alias dla float64
float32  │ float   │ 32 bit  │ ~7 cyfr precyzji
float64  │ double  │ 64 bit  │ ~15 cyfr precyzji
bool     │ i1      │ 1 bit   │ true=1, false=0
string   │ i8*     │ wskaźnik│ bufor 256 bajtów na stosie
```

### Konwersje jawne (cast) i niejawne (coerce)

```
        int ──────────────────────────────────────────┐
         │  sitofp i32→float         sitofp i32→double│
         ▼                                            ▼
      float32 ──────────────────────────────────── double
         │  fpext float→double                        │
         └────────────────────────────────────────────┘
                      fptrunc double→float

  int ←→ bool:   icmp ne i32 v, 0   /   zext i1 v to i32
float ←→ bool:   fcmp une f v, 0.0  /   sitofp po zext
  int  →  float: sitofp i32 v to float/double
float  →  int:   fptosi float/double v to i32 (obcięcie)
```

### Promocja w printf (ABI C)

C ABI promuje `float` do `double` przy przekazywaniu do funkcji wariadycznej:
```java
// float nie można przekazać bezpośrednio do printf!
String ext = floatToDouble(val);       // fpext float val to double
main_text += "... @printf(@fmt_f64_p, double " + ext + ")\n";
```

---

## 8. Short-circuit evaluation

AND i OR używają warunkowych skoków i węzłów phi zamiast zwykłych operacji logicznych.

### AND (b1 AND b2)

Jeśli `b1 = false`, wynik jest `false` bez obliczania `b2`.

```llvm
; [bieżący blok] — oblicz b1
%1 = load i1, i1* %a
br i1 %1, label %and_eval_0, label %and_false_0

and_eval_0:              ; b1 = true → oblicz b2
  %2 = load i1, i1* %b
  br label %and_end_0

and_false_0:             ; b1 = false → wynik = false (b2 pominięte)
  br label %and_end_0

and_end_0:
  %3 = phi i1 [%2, %and_eval_0], [false, %and_false_0]
  ; %3 = wynik a AND b
```

### OR (b1 OR b2)

Jeśli `b1 = true`, wynik jest `true` bez obliczania `b2`.

```llvm
; [bieżący blok] — oblicz b1
%1 = load i1, i1* %a
br i1 %1, label %or_true_0, label %or_eval_0

or_eval_0:               ; b1 = false → oblicz b2
  %2 = load i1, i1* %b
  br label %or_end_0

or_true_0:               ; b1 = true → wynik = true (b2 pominięte)
  br label %or_end_0

or_end_0:
  %3 = phi i1 [%2, %or_eval_0], [true, %or_true_0]
  ; %3 = wynik a OR b
```

### Węzeł phi

`phi i1 [v1, %l1], [v2, %l2]` wybiera wartość w zależności od tego,
z którego bloku przyszło wykonanie. Wymagane jest śledzenie `currentBlock`
(aktualnie aktywnego bloku) aby phi wiedziało, jaki label przypisać do wartości `v1`.

---

## 9. Tablice z zakresami indeksów

### Deklaracja

```
MyLang:   int[5] arr          →  %arr = alloca [5 x i32]
MyLang:   int[1..3] ranged    →  %ranged = alloca [3 x i32]
```

Niezależnie od zakresu użytkownika, LLVM zawsze indeksuje od 0.
Metadane zakresu są przechowywane w `ArrayInfo`.

### Korekcja indeksu

Dla `int[1..3] ranged` użytkownik używa indeksów 1, 2, 3.
Przed GEP wykonujemy korekcję: `adjusted = user_index - startIndex`.

```llvm
; ranged[2] = 200
; user_index = 2, startIndex = 1
%idx = ... (wartość 2)
%adj = sub i32 %idx, 1      ; adjusted = 2 - 1 = 1
%gep = getelementptr inbounds [3 x i32], [3 x i32]* %ranged, i32 0, i32 %adj
store i32 200, i32* %gep
```

Dla `int[5] arr` (startIndex = 0) korekcja jest pomijana (brak `sub`).

### Wypisanie wszystkich elementów (compile-time unroll)

Zamiast generować pętlę LLVM (która wymagałaby phi + branch), rozwijamy ją
statycznie w czasie kompilacji:

```llvm
; print arr  (int[3] arr)
%1 = getelementptr inbounds [3 x i32], [3 x i32]* %arr, i32 0, i32 0
%2 = load i32, i32* %1
%3 = call i32 @printf(@fmt_i32_p, i32 %2)
%4 = getelementptr inbounds [3 x i32], [3 x i32]* %arr, i32 0, i32 1
%5 = load i32, i32* %4
%6 = call i32 @printf(@fmt_i32_p, i32 %5)
; ... (dla każdego elementu)
```

### Zmienne stringowe

String to dwie alokacje: wskaźnik i bufor.

```llvm
%s     = alloca i8*              ; wskaźnik do aktualnego stringa
%s_buf = alloca [256 x i8]       ; bufor na max 255 znaków + '\0'
%1     = getelementptr inbounds [256 x i8], [256 x i8]* %s_buf, i32 0, i32 0
store i8* %1, i8** %s            ; wskaźnik ustawiony na bufor
```

Przy przypisaniu string używamy `strcpy` (zamiast przypisania wskaźnika):
```llvm
%buf = getelementptr inbounds [256 x i8], [256 x i8]* %s_buf, i32 0, i32 0
%r   = call i8* @strcpy(i8* %buf, i8* %src)
; %r jest nieużywane, ale MUSI dostać numer rejestru (LLVM SSA)
```

---

## 10. Obsługa błędów

### Błędy leksykalne i składniowe

Wykrywane przez ANTLR, przechwytywane przez `MyLangErrorListener`.
Program zatrzymuje się przed analizą semantyczną.

```
Błąd składniowy [linia 3:7]: nieoczekiwany token 'xyz'
  Szczegóły: mismatched input 'xyz' expecting ...

Kompilacja przerwana – błędy składniowe.
```

### Błędy semantyczne

Wykrywane przez `LLVMActions` podczas przechodzenia AST.
Wszystkie błędy są zbierane i raportowane, program nie generuje IR.

```
Błąd semantyczny, linia 5: niezadeklarowana zmienna 'x'
Błąd semantyczny, linia 7: niezgodność typów: oczekiwano int, podano real

Kompilacja przerwana – błędy semantyczne.
```

Rodzaje sprawdzanych błędów:
- Użycie niezadeklarowanej zmiennej/tablicy
- Podwójna deklaracja
- Niezgodność typów przy przypisaniu/inicjalizacji
- Operacje arytmetyczne na typach nieliczbowych
- Operacje logiczne na typach nieliczbowych
- Niedozwolone rzutowanie
- Nieprawidłowy zakres tablicy (start > end)

---

## 11. Kompletny przykład kompilacji

### Kod źródłowy (MyLang)

```
int x = 5
int y = 3
bool big = x > y
print big
```

### Wygenerowany LLVM IR

```llvm
declare i32 @printf(i8*, ...)
declare i32 @__isoc99_scanf(i8*, ...)
declare i8* @strcpy(i8*, i8*)

; ── Format strings ───────────────────────────────────────────────
@fmt_i32_p = constant [4 x i8] c"%d\0A\00"
...

; ── Ciało programu ────────────────────────────────────────────────
define i32 @main() nounwind {
  ; int x = 5
  %x = alloca i32
  store i32 5, i32* %x

  ; int y = 3
  %y = alloca i32
  store i32 3, i32* %y

  ; bool big = x > y
  %big = alloca i1
  %1 = load i32, i32* %x
  %2 = load i32, i32* %y
  %3 = icmp sgt i32 %1, %2     ; signed greater-than
  store i1 %3, i1* %big

  ; print big
  %4 = load i1, i1* %big
  %5 = zext i1 %4 to i32        ; bool → int dla printf
  %6 = call i32 (i8*, ...) @printf(
         i8* getelementptr inbounds ([4 x i8], [4 x i8]* @fmt_i32_p, i32 0, i32 0),
         i32 %5)

  ret i32 0
}
```

### Wyjście programu

```
1
```

---

## Używane narzędzia

| Narzędzie       | Wersja  | Rola                                          |
|-----------------|---------|-----------------------------------------------|
| ANTLR4          | 4.13.1  | Generowanie lexera i parsera z gramatyki       |
| Java JDK        | 11+     | Kompilacja i uruchomienie kompilatora          |
| LLVM (`lli`)    | dowolna | Interpretacja/wykonanie wygenerowanego IR      |
| `clang`         | dowolna | Kompilacja IR do binarki (alternatywnie do lli)|

---

*Dokument opisuje kompilator MyLang Etap 1.*
