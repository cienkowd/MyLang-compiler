# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Co to jest

Kompilator języka **MyLang** — prostego języka imperatywnego kompilowanego do **LLVM IR**.  
Stack: **ANTLR4** (lexer/parser) + **Java** (visitor semantyczny + generator kodu).

Pliki źródłowe: `src/*.java` i `src/MyLang.g4`.  
Pliki generowane przez ANTLR4 trafiają do `src/gen/` i **nie są commitowane**.

---

## Komendy

### Budowanie

```bash
# Pełna budowa: ANTLR gen → javac
make

# Tylko regeneruj lexer/parser z gramatyki
make gen

# Tylko kompiluj Javę (gdy gramatyka się nie zmieniła)
make compile
```

> **Wymaga:** `antlr-4.13.1-complete.jar` w katalogu projektu.  
> Pobierz: `wget https://www.antlr.org/download/antlr-4.13.1-complete.jar`

### Uruchamianie

```bash
# Kompiluj plik .ml i uruchom przez lli (lub clang)
make run FILE=tests/test_basic.ml

# Tylko wypisz wygenerowany LLVM IR (bez uruchamiania)
make ir FILE=tests/test_basic.ml

# Uruchom ręcznie
java -cp "antlr-4.13.1-complete.jar:build" Main tests/test_basic.ml > out.ll
lli out.ll
```

### Testy

```bash
# Wszystkie testy (każdy plik .ml z tests/)
make test

# Jeden test
make run FILE=tests/test_arrays.ml

# Podgląd IR dla konkretnego testu
make ir FILE=tests/test_bool.ml
```

### Czyszczenie

```bash
make clean   # usuwa build/ i src/gen/
```

---

## Architektura

Pipeline: `plik .ml → Lexer → Parser → AST → LLVMActions (visitor) → LLVMGenerator → stdout (.ll)`

### Rola każdego pliku

| Plik | Rola |
|------|------|
| `src/MyLang.g4` | Gramatyka ANTLR4 — definiuje składnię. Zmiany tutaj wymagają `make gen` |
| `src/LLVMActions.java` | Visitor semantyczny — przechodzi AST, sprawdza typy, wywołuje generator |
| `src/LLVMGenerator.java` | Generator kodu — metody emitujące konkretne instrukcje LLVM IR |
| `src/VarType.java` | Enum typów MyLang → LLVM (INT→i32, REAL/FLOAT64→double, FLOAT32→float, BOOL→i1) |
| `src/Value.java` | Para `(rejestr LLVM, VarType)` — wynik ewaluacji każdego wyrażenia |
| `src/ArrayInfo.java` | Metadane tablicy: typ elementu, startIndex, endIndex, size |
| `src/Main.java` | Punkt wejścia + `MyLangErrorListener` (błędy ANTLR po polsku) |

### Jak działa generator kodu

`LLVMGenerator` buduje IR jako dwa stringi:
- `header_text` — globalne stałe (format strings, literały string)
- `main_text` — instrukcje w ciele `@main`

Pole `reg` to licznik sekwencyjny rejestrów LLVM (`%1`, `%2`, ...). **Każda** instrukcja produkująca wartość (load, add, call zwracające nie-void) musi dostać numer — LLVM waliduje sekwencję i zgłasza błąd przy przeskoku.

### Jak działa visitor

`LLVMActions.visit(ctx)` zwraca `Value(rejestr, typ)` dla wyrażeń, `null` dla instrukcji.  
Typowy wzorzec dla operacji binarnej:
```java
Value left  = visit(ctx.leftChild());   // rekurencja
Value right = visit(ctx.rightChild());
Value[] uni = unifyNumeric(left, right, ctx);  // promocja typów
return new Value(LLVMGenerator.add(uni[0].name, uni[1].name, uni[0].type), uni[0].type);
```

---

## Kluczowe pułapki

### 1. Numeracja rejestrów LLVM (najważniejsze)

`call` zwracające wartość inną niż `void` **zawsze** musi dostać numer rejestru, nawet jeśli wynik jest nieużywany:
```java
// ŹLE — LLVM zgłosi: "instruction expected to be numbered '%N'"
LLVMGenerator.main_text += "  call i8* @strcpy(...)\n";

// DOBRZE
String r = LLVMGenerator.nextReg();
LLVMGenerator.main_text += "  " + r + " = call i8* @strcpy(...)\n";
```

### 2. Java `+=` ze skutkami ubocznymi

Gdy metoda wywoływana w RHS modyfikuje `main_text` (jak `loadStringBufPtr`), nie wolno jej wstawiać bezpośrednio do `main_text +=`. Java oblicza stary `main_text` przed dodaniem — i nadpisuje skutek uboczny:
```java
// ŹLE — GEP z loadStringBufPtr zostanie utracony
LLVMGenerator.main_text += "  call @strcpy(" + loadStringBufPtr(id) + ", ...)\n";

// DOBRZE — wywołaj przed +=
String bufPtr = loadStringBufPtr(id);
LLVMGenerator.main_text += "  call @strcpy(" + bufPtr + ", ...)\n";
```

### 3. Zmiany w gramatyce wymagają `make gen`

Dodanie reguły, tokenu lub etykiety `#nazwaAlternatywy` w `MyLang.g4` wymaga ponownego uruchomienia ANTLR, które regeneruje `src/gen/`. Bez tego kompilator Javy widzi stare interfejsy.

### 4. Token `DOTDOT` i literały REAL

`REAL` wymaga cyfr **po** kropce (`[0-9]+ '.' [0-9]+`), inaczej `1..5` byłoby tokenizowane jako `REAL(1.)` + reszta zamiast `INT DOTDOT INT`. `DOTDOT` musi być zdefiniowany przed `REAL` w gramatyce.

### 5. Kolejność alternatyw w regule `stat`

ANTLR dopasowuje **pierwszą** pasującą alternatywę. Ważna kolejność:
- `rangedArrayDecl` przed `arrayDecl` (oba zaczynają się od `typeName '['`)
- `readArrayStat` przed `readStat`
- `printIdStat` przed `printStat` (by `print x` trafiło do `printIdStat`, nie do `printStat`)
- `arrayRef` przed `idRef` w `primaryExpr`

### 6. Float w printf

C ABI promuje `float` do `double` w funkcjach wariadycznych. Zawsze używaj `fpext` przed przekazaniem `float` do `printf`:
```java
String ext = floatToDouble(val);  // fpext float → double
// potem printf z double ext
```

### 7. Short-circuit AND/OR — `currentBlock`

Przy budowaniu węzłów `phi` po AND/OR potrzebna jest nazwa bloku, z którego pochodzi wartość prawej strony. `LLVMGenerator.currentBlock` jest aktualizowany przez `emitLabel()`. Nigdy nie aktualizuj go ręcznie — tylko przez `emitLabel`.

---

## Dodawanie nowych funkcji

### Nowy operator

1. Dodaj token i regułę w `MyLang.g4` (z etykietą `#nazwaOp`)
2. `make gen`
3. Dodaj `visitNazwaOp` w `LLVMActions.java`
4. Jeśli potrzebna instrukcja LLVM — dodaj metodę w `LLVMGenerator.java`

### Nowy typ

1. Dodaj wartość do `VarType.java` z metodą `llvm()`
2. Dodaj słowo kluczowe do reguły `typeName` w gramatyce + `make gen`
3. Rozszerz `resolveType`, `coerce`, `cast`, `unifyNumeric` w `LLVMActions.java`
4. Rozszerz metody I/O w `LLVMGenerator.java`

### Nowa instrukcja

1. Dodaj alternatywę do reguły `stat` w gramatyce (z etykietą) + `make gen`
2. Implementuj `visitNazwaAlternatywy` w `LLVMActions.java`

---

## Testy — oczekiwane wyjścia

| Plik testu | Oczekiwane wyjście |
|---|---|
| `test_basic.ml` | `11` / `3.140000` / `3` |
| `test_bool.ml` | `0` / `1` / `1` / `0` / `1` / `0` |
| `test_comparisons.ml` | `1` / `0` / `1` / `1` / `0` |
| `test_float_precision.ml` | 7 wartości float (różnice przy float32 vs float64) |
| `test_string.ml` | `Hello, World!` + echo wejścia użytkownika |
| `test_arrays.ml` | `10`/`20`/`30`/`40`/`50` / `150` / `100`/`200`/`300` / `60` / 3× floaty |
| `test_errors.ml` | `42` (błędy są zakomentowane) |

`test_string.ml` czeka na input użytkownika (`read imie`) — wpisz coś i naciśnij Enter.

---

## Szczegółowa dokumentacja

- [`ARCHITEKTURA.md`](ARCHITEKTURA.md) — pipeline kompilacji, schematy IR dla short-circuit i tablic, przykład end-to-end
- [`README.md`](README.md) — składnia języka MyLang, quick start
