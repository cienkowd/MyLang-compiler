# Kompendium wiedzy — Kompilator MyLang

## Spis treści

1. [Ogólna architektura — potok kompilacji](#1-ogólna-architektura)
2. [Gramatyka — MyLang.g4](#2-gramatyka--mylang-g4)
3. [System typów — VarType.java i Value.java](#3-system-typów)
4. [Metadane struktur danych — ArrayInfo, MatrixInfo, FuncInfo, StructInfo](#4-metadane-struktur-danych)
5. [Generowanie kodu — LLVMGenerator.java](#5-generowanie-kodu--llvmgenerator)
6. [Analiza semantyczna — LLVMActions.java](#6-analiza-semantyczna--llvmactions)
7. [Punkt wejścia — Main.java i ErrorListener.java](#7-punkt-wejścia)
8. [Kluczowe decyzje projektowe](#8-kluczowe-decyzje-projektowe)
9. [Szczegóły LLVM IR](#9-szczegóły-llvm-ir)
10. [Tablice 1D — implementacja i zakres indeksów](#10-tablice-1d)
11. [Tablice 2D — macierze](#11-tablice-2d--macierze)
12. [Short-circuit evaluation — OR i AND](#12-short-circuit-evaluation)
13. [Instrukcje sterujące — if/else, while, for](#13-instrukcje-sterujące)
14. [Funkcje użytkownika](#14-funkcje-użytkownika)
15. [Struktury i klasy](#15-struktury-i-klasy)
16. [System konwersji typów — coerce vs cast](#16-konwersja-typów)
17. [Obsługa wejścia-wyjścia — printf i scanf](#17-wejście-wyjście)
18. [Typowe pytania obrończe i odpowiedzi](#18-typowe-pytania-obrończe)

---

## 1. Ogólna architektura

Kompilator to program jednoprzebiegowy, który tłumaczy kod źródłowy MyLang na LLVM IR, a wynik drukuje na standardowe wyjście. Potok wygląda następująco:

```
plik .ml
   │
   ▼
MyLangLexer          — tokenizacja (ANTLR4-generated)
   │
   ▼
MyLangParser         — budowa drzewa składniowego CST (ANTLR4-generated)
   │
   ▼
LLVMActions          — visitor przechodzący po CST:
   │                    · analiza semantyczna (typy, deklaracje)
   │                    · wywołania LLVMGenerator
   │
   ▼
LLVMGenerator        — builder LLVM IR (stringi do stdout)
   │
   ▼
wydruk na stdout     — gotowy plik .ll
```

Pliki generowane przez ANTLR4 (`MyLangLexer.java`, `MyLangParser.java`, `MyLangBaseVisitor.java`, `MyLangVisitor.java`) są przechowywane w `src/gen/` i nie są ręcznie edytowane.

### Struktura wyjściowego pliku .ll (Etap 2)

Po rozszerzeniu o funkcje, struktury i klasy plik `.ll` generowany przez `LLVMGenerator.generate()` ma następującą strukturę:

```
1. Deklaracje zewnętrznych funkcji     (declare @printf, @scanf, @strcpy...)
2. Typy struktur/klas                  (%Vec2 = type { double, double })
3. Globalne literały stringów          (@str0 = ..., @fmt_i32_p = ...)
4. Definicje funkcji użytkownika       (define i32 @max(...) { ... })
5. Definicja @main                     (define i32 @main() { entry: ... ret i32 0 })
```

Kolejność jest istotna: definicje typów muszą poprzedzać funkcje je używające, a funkcje muszą być zadeklarowane/zdefiniowane przed wywołaniem (w LLVM IR wywołanie nieznanego symbolu jest błędem).

---

## 2. Gramatyka — MyLang.g4

### Co robi

Plik `MyLang.g4` jest specyfikacją języka MyLang w notacji ANTLR4. Zawiera reguły leksykalne (tokeny) i składniowe (parser rules). Na jego podstawie ANTLR4 generuje lexer i parser oraz interfejs visitora.

### Reguła startowa

```antlr
prog: topLevel* EOF ;

topLevel
    : funcDecl
    | structDecl
    | classDecl
    | stat
    ;
```

W Etapie 2 NEWLINE przestał być tokenem-separatorem — jest ignorowany (`-> skip`). Program to sekwencja deklaracji najwyższego poziomu: funkcji, struktur, klas i instrukcji. Bloki kodu są otoczone klamrami `{ }`.

**Dlaczego rezygnacja z NEWLINE?** Bloki `{ }` wymagają wielolinijkowości — ciało funkcji jest rozpisane na wiele linii. Token NEWLINE jako separator instrukcji kolidowałby z tym: parser oczekiwałby instrukcji po każdym NEWLINE, ale blok `{` otwiera nowy kontekst. Usunięcie NEWLINE jako tokenu semantycznego upraszcza gramatykę — separator staje się niewidoczny, jak w C/Java.

### Bloki i instrukcje

```antlr
block: '{' stat* '}' ;
```

Blok to zero lub więcej instrukcji ujętych w klamry. Używany w: `funcDecl`, `methodDecl`, `ifStat`, `whileStat`, `forStat`.

### Pełna lista instrukcji (`stat`) z kolejnością

```antlr
stat
    : typeName '[' INT ']' '[' INT ']' ID              #matrixDecl
    | typeName '[' INT DOTDOT INT ']' ID               #rangedArrayDecl
    | typeName '[' INT ']' ID                          #arrayDecl
    | typeName ID ('=' expr)?                          #varDecl
    | ID '[' expr ']' '[' expr ']' '=' expr            #matrixAssign
    | ID '[' expr ']' '=' expr                         #arrayAssign
    | ID '.' ID '=' expr                               #fieldAssign
    | ID '=' expr                                      #assign
    | 'read' ID '[' expr ']' '[' expr ']'              #readMatrixStat
    | 'read' ID '[' expr ']'                           #readArrayStat
    | 'read' ID                                        #readStat
    | 'print' ID                                       #printIdStat
    | 'print' expr                                     #printStat
    | 'if' '(' expr ')' block
        ('else' 'if' '(' expr ')' block)*
        ('else' block)?                                #ifStat
    | 'while' '(' expr ')' block                       #whileStat
    | 'for' ID '=' expr 'to' expr block                #forStat
    | 'return' expr?                                   #returnStat
    | ID '(' argList? ')'                              #funcCallStat
    | ID '.' ID '(' argList? ')'                       #methodCallStat
    ;
```

**Kolejność alternatyw ma kluczowe znaczenie** (ANTLR4 jest deterministyczny LL):

- `matrixDecl` przed `arrayDecl` — oba zaczynają od `typeName '[' INT`; ANTLR musi zobaczyć `']' '[' INT` (dwa wymiary) zanim wybierze wariant 2D.
- `rangedArrayDecl` przed `arrayDecl` — oba zaczynają od `typeName '[' INT`; `DOTDOT` po pierwszym INT rozstrzyga.
- `matrixAssign` przed `arrayAssign` — `ID '[' expr ']' '[' expr ']'` vs `ID '[' expr ']'`.
- `fieldAssign` przed `assign` — `ID '.' ID` vs `ID`.
- `printIdStat` przed `printStat` — `print x` pasuje do obu; samo `ID` jest ściślejszym dopasowaniem.

### Deklaracje funkcji i metod

```antlr
funcDecl: 'fun' returnTypeName ID '(' paramList? ')' block   #funcDeclRule ;

returnTypeName
    : typeName   #retTypeNamed
    | 'void'     #retTypeVoid
    ;

paramList: param (',' param)* ;
param: typeName ID ;
```

Funkcja ma nazwę, opcjonalną listę parametrów i blok. Zwracany typ może być `void` (procedura) lub dowolny `typeName`.

### Struktury i klasy

```antlr
structDecl: 'struct' ID '{' fieldDecl* '}'   #structDeclRule ;

classDecl: 'class' ID '{' classMember* '}'   #classDeclRule ;

classMember
    : fieldDecl      #classMemberField
    | methodDecl     #classMemberMethod
    ;

fieldDecl: typeName ID ;

methodDecl: 'fun' returnTypeName ID '(' paramList? ')' block ;
```

Struct to rekord z polami. Class to struct z metodami. Różnica syntaktyczna: `struct` zawiera tylko `fieldDecl`, `class` może zawierać zarówno pola jak i metody (odróżniane przez `classMember`).

### Hierarchia wyrażeń

Gramatyka enkoduje pierwszeństwo operatorów przez zagnieżdżenie reguł:

```
orExpr < xorExpr < andExpr < notExpr < cmpExpr < addExpr < mulExpr < castExpr < unaryExpr < primaryExpr
```

### primaryExpr — rozszerzony o dostęp do elementów strukturowanych

```antlr
primaryExpr
    : INT                               #intLit
    | REAL                              #realLit
    | 'true'                            #trueLit
    | 'false'                           #falseLit
    | STRING_LIT                        #stringLit
    | ID '[' expr ']' '[' expr ']'      #matrixRef
    | ID '[' expr ']'                   #arrayRef
    | ID '(' argList? ')'               #funcCallExpr
    | ID '.' ID '(' argList? ')'        #methodCallExpr
    | ID '.' ID                         #fieldRef
    | ID                                #idRef
    | '(' expr ')'                      #parenExpr
    ;
```

`matrixRef` przed `arrayRef` (dwa nawiasy vs jeden). `funcCallExpr`/`methodCallExpr`/`fieldRef` przed `idRef` (bardziej szczegółowe dopasowania pierwsze).

### Słowa kluczowe zadeklarowane przed ID

```antlr
TO:     'to';
FUN:    'fun';
STRUCT: 'struct';
CLASS:  'class';
VOID:   'void';
RETURN: 'return';
IF:     'if';
ELSE:   'else';
WHILE:  'while';
FOR:    'for';
```

ANTLR4 przypisuje tokenom literały pierwszeństwo nad regułami leksykalnymi. Jawne deklaracje tokenów przed regułą `ID` gwarantują, że `to`, `fun`, `while` itp. są rozpoznawane jako słowa kluczowe, a nie identyfikatory. Bez tego `for k = 1 to 10` tokenizowałoby `to` jako `ID("to")` i parsowanie pętli `for` zawiodłoby.

### Token DOTDOT

```antlr
DOTDOT: '..';
```

Musi być zdefiniowany przed `REAL`. Gdyby `REAL` dopuszczał `1.` (bez cyfr po kropce), sekwencja `1..5` zostałaby ztokenizowana jako `REAL("1.")` + `DOT` + `INT("5")`. Reguła `REAL` wymaga cyfr po obu stronach (`[0-9]+ '.' [0-9]+`), więc `1..5` → `INT DOTDOT INT`.

---

## 3. System typów

### VarType.java

`VarType` to enum z wartościami: `INT`, `REAL`, `FLOAT32`, `FLOAT64`, `BOOL`, `STRING`, `STRUCT`, `VOID`, `UNKNOWN`.

Kluczowe metody:

- `llvm()` — zwraca odpowiedni typ LLVM IR (`i32`, `double`, `float`, `double`, `i1`, `i8*`; dla `STRUCT`/`VOID` zwraca `void` lub pusty string — te typy traktowane są kontekstowo).
- `isFloat()` — `true` dla `REAL`, `FLOAT32`, `FLOAT64`.
- `isNumeric()` — `true` dla `INT` i wszystkich float.

**`STRUCT`** — specjalny marker mówiący, że wartość jest wskaźnikiem do struktury/klasy alokowanej na stosie. Faktyczna nazwa typu LLVM (np. `%Vec2`) jest przechowywana w polu `structTypeName` klasy `Value`.

**`VOID`** — typ zwrotny dla funkcji-procedur (nie zwracają wartości). Używany w `FuncInfo.returnType`. `visitReturnStat` emituje `ret void` gdy typ funkcji to VOID.

**Dlaczego `REAL` i `FLOAT64` mapują na `double`?** W LLVM IR nie ma różnicy — oba są 64-bitowe. Rozróżnienie istnieje tylko na poziomie języka MyLang.

**Dlaczego enum, a nie klasy?** Typy są skończonym, zamkniętym zbiorem. Enum gwarantuje bezpieczne porównanie przez `==` i działa sprawnie w `switch`.

### Value.java

```java
public class Value {
    public final String name;           // rejestr LLVM lub literał, np. "%3" lub "42"
    public final VarType type;          // typ wartości
    public final String structTypeName; // np. "Vec2" gdy type == STRUCT, null w pozostałych przypadkach
}
```

`Value` to trójka (rejestr LLVM, typ, opcjonalna nazwa struktury). Każde wyrażenie zwraca `Value` — visitor propaguje wartości w górę drzewa.

**Dlaczego niemodyfikowalny (final)?** LLVM IR jest w formie SSA — raz wyliczona wartość nigdy się nie zmienia. Niemutowalność `Value` odzwierciedla tę właściwość.

---

## 4. Metadane struktur danych

### ArrayInfo.java

```java
public class ArrayInfo {
    public final VarType elementType;
    public final int startIndex;  // 0 dla int[5], 1 dla int[1..3]
    public final int endIndex;
    public final int size;        // endIndex - startIndex + 1
}
```

`ArrayInfo` przechowuje wszystko, co kompilator musi wiedzieć o tablicy 1D w czasie kompilacji. Przy dostępie do elementu kompilator przelicza indeks użytkownika na fizyczny przez odjęcie `startIndex` (metoda `adjustIndex` w `LLVMActions`).

### MatrixInfo.java

```java
public class MatrixInfo {
    public final VarType elementType;
    public final int rows;
    public final int cols;

    public String llvmRowType() { return "[" + cols + " x " + elementType.llvm() + "]"; }
    public String llvmMatType() { return "[" + rows + " x " + llvmRowType() + "]"; }
}
```

`MatrixInfo` opisuje macierz 2D. Metoda `llvmRowType()` generuje typ LLVM wiersza, a `llvmMatType()` — typ całej macierzy. Dla `int[3][4]`: `llvmRowType()` = `[4 x i32]`, `llvmMatType()` = `[3 x [4 x i32]]`.

W `LLVMActions` macierze są przechowywane w oddzielnej mapie `matrices: Map<String, MatrixInfo>`.

### FuncInfo.java

```java
public class FuncInfo {
    public final String name;
    public final VarType returnType;        // null / VOID dla procedur
    public final List<String> paramNames;
    public final List<VarType> paramTypes;

    public boolean isVoid() { return returnType == null || returnType == VarType.VOID; }
}
```

`FuncInfo` rejestruje sygnaturę funkcji. Jest wpisywana do mapy `functions` w `LLVMActions` **przed** odwiedzeniem ciała funkcji — umożliwia to rekurencję (funkcja może wywołać siebie).

### StructInfo.java

```java
public class StructInfo {
    public final String name;
    public final List<String> fieldNames;
    public final List<VarType> fieldTypes;
    public final boolean isClass;
    public final Map<String, FuncInfo> methods; // puste dla struct

    public int fieldIndex(String fieldName) { return fieldNames.indexOf(fieldName); }
    public String llvmTypeName() { return "%" + name; }
}
```

`StructInfo` opisuje strukturę lub klasę. `fieldIndex` zwraca indeks pola (0-based) — potrzebny do GEP. `llvmTypeName()` generuje nazwę LLVM IR (np. `%Vec2`).

---

## 5. Generowanie kodu — LLVMGenerator

### Co robi

`LLVMGenerator` to klasa statyczna (wszystkie pola i metody `static`) pełniąca rolę buildera LLVM IR. Akumuluje kod do kilku buforów, które są scalane w metodzie `generate()`.

### Bufory

| Bufor | Zawartość |
|---|---|
| `header_text` | Stałe globalne: format strings, literały stringów |
| `structs_text` | Definicje typów LLVM: `%Vec2 = type { ... }` |
| `funcs_text` | Definicje funkcji użytkownika |
| `main_text` | Ciało funkcji `@main` |

`generate()` skleja je w kolejności: deklaracje extern → structs_text → header_text → funcs_text → definicja @main.

### Kluczowe pola

| Pole | Opis |
|---|---|
| `reg` | Globalny licznik rejestrów SSA; `nextReg()` zwraca `%N` i inkrementuje |
| `labelCount` | Licznik etykiet bloków; unikalne nazwy dla if/while/for |
| `readBufCount` | Licznik buforów dla `read bool` |
| `strLitCount` | Licznik stałych globalnych stringów |
| `currentBlock` | Nazwa aktualnie otwartego bloku — potrzebna dla `phi` |
| `blockTerminated` | `true` jeśli ostatnia instrukcja w bloku to terminator (`ret`/`br`) |
| `inUserFunc` | `true` gdy generator jest w kontekście funkcji użytkownika |

### Kontekst funkcji — save/restore

Kiedy kompilator wchodzi w ciało funkcji użytkownika, musi emitować kod do `funcs_text` zamiast `main_text`, ale stan rejestrów `reg` i etykiet `labelCount` musi być współdzielony (globalny). Mechanizm save/restore:

```java
// Przed generowaniem funkcji — zapisz kontekst @main
savedReg = reg;
savedBlock = currentBlock;
savedTerminated = blockTerminated;
savedMainText = main_text;
main_text = new StringBuilder(); // nowy bufor dla tej funkcji
inUserFunc = true;

// ... generowanie ciała funkcji ...

// Po zakończeniu — przywróć kontekst @main
funcs_text.append(main_text);
main_text = savedMainText;
reg = savedReg;
currentBlock = savedBlock;
blockTerminated = savedTerminated;
inUserFunc = false;
```

### blockTerminated — flaga podwójnych terminatorów

LLVM IR wymaga, by każdy blok podstawowy kończył się dokładnie jednym terminatorem (`ret`, `br`, `condBr`). Problem pojawia się gdy:

- `if` generuje `br if_end` po wszystkich gałęziach, ale gałąź zawierająca `return` już emituje `ret`.
- `for`/`while` emitują `br cond_label` po ciele pętli, ale ciało może kończyć się `ret`.

Rozwiązanie: flaga `blockTerminated`. Reguły:

- `emitReturn` i `condBr` — ustawiają `blockTerminated = true`.
- `br(label)` — emituje skok **tylko jeśli** `!blockTerminated`, potem ustawia `true`.
- `emitLabel(label)` — emituje etykietę i **resetuje** `blockTerminated = false`.

### Metody generatora — wielofunkcyjność

```java
// Otwórz funkcję użytkownika (zapisuje kontekst, emituje sygnaturę + entry:)
void beginFunction(String llvmName, VarType retType, List<String> paramNames, List<VarType> paramTypes)

// Otwórz metodę klasy (jak wyżej, ale z pierwszym arg %ClassName* %self)
void beginStructMethod(String llvmName, String className, VarType retType, ...)

// Zamknij funkcję (emituje domyślny ret, dołącza do funcs_text, przywraca kontekst)
void endFunction(VarType retType)

// Emituj instrukcję return
void emitReturn(String valReg, VarType type)

// Wywołanie funkcji (zwraca rejestr z wynikiem lub "" dla void)
String callFunction(String name, VarType retType, List<String> argRegs, List<VarType> argTypes)

// Wywołanie metody
String callMethod(String name, String className, VarType retType, String selfPtr, ...)
```

### Metody generatora — struktury

```java
// Deklaracja typu LLVM: %Name = type { T1, T2, ... }
void declareStructType(String name, List<String> fieldNames, List<VarType> fieldTypes)

// Alokacja zmiennej struktury: %id = alloca %Name
void allocaStruct(String id, String typeName)

// GEP do pola struktury (przez alloca): %ptr = getelementptr %Name, %Name* %id, i32 0, i32 fieldIdx
String structFieldPtr(String id, String typeName, int fieldIdx, VarType fieldType)

// GEP do pola struktury (przez wskaźnik): %ptr = getelementptr %Name, %Name* %ptr, i32 0, i32 fieldIdx
String structFieldPtrFromPtr(String ptr, String typeName, int fieldIdx)
```

### Metody generatora — macierze 2D

```java
// Alokacja: %id = alloca [R x [C x T]]
void declareMatrix(String id, VarType elemType, int rows, int cols)

// GEP dwupoziomowy do elementu macierzy
String matrixGEP(String id, String rowReg, String colReg, VarType elemType, int rows, int cols)

// Compile-time unrolled print całej macierzy
void printMatrixAll(String id, VarType elemType, int rows, int cols)
```

### Dlaczego klasa statyczna?

Kompilator obsługuje jeden program na raz. Klasa statyczna eliminuje konieczność przekazywania referencji przez cały visitor. W kompilatorze produkcyjnym (wieloplikowym, wielowątkowym) należałoby przerobić na instancję.

### alloca / load / store — wzorzec zmiennych

Każda zmienna `x` typu `T` jest reprezentowana jako slot na stosie:

```llvm
%x = alloca i32        ; rezerwacja miejsca
store i32 5, i32* %x   ; zapis wartości
%1 = load i32, i32* %x ; odczyt wartości → do rejestru %1
```

LLVM optymalizator `mem2reg` automatycznie promuje `alloca` do prawdziwych rejestrów SSA.

---

## 6. Analiza semantyczna — LLVMActions

### Co robi

`LLVMActions` rozszerza `MyLangBaseVisitor<Value>`. Visitor przechodzi po CST i dla każdego węzła: sprawdza poprawność semantyczną, wywołuje metody `LLVMGenerator`, zwraca `Value`.

### Pola stanu

```java
// Zmienne skalarne: nazwa → typ
private Map<String, VarType> variables;

// Tablice 1D: nazwa → metadane
private Map<String, ArrayInfo> arrays;

// Macierze 2D: nazwa → metadane
private Map<String, MatrixInfo> matrices;

// Zmienne strukturowe: nazwa zmiennej → nazwa typu struktury
private Map<String, String> structVars;

// Rejestr funkcji (globalny): nazwa → sygnatura
private final Map<String, FuncInfo> functions;

// Rejestr typów struktur/klas (globalny): nazwa → opis
private final Map<String, StructInfo> structs;

// Kontekst aktualnie kompilowanej funkcji
private FuncInfo currentFunc;

// Nazwa aktualnie kompilowanej klasy (dla metod)
private String currentClassName;

// Licznik błędów semantycznych
private int errors;
```

### Zarządzanie zakresem zmiennych

Przy wejściu w ciało funkcji `visitFuncDeclRule` zapisuje aktualny stan czterech map zmiennych:

```java
Map<String, VarType> savedVariables = new HashMap<>(variables);
Map<String, ArrayInfo> savedArrays  = new HashMap<>(arrays);
Map<String, MatrixInfo> savedMatrices = new HashMap<>(matrices);
Map<String, String> savedStructVars = new HashMap<>(structVars);

// Wyczyść lokalne mapy (nowy zakres) i dodaj parametry
variables.clear();
// ...dodaj parametry funkcji...

// Odwiedź ciało
visit(ctx.block());

// Przywróć zakres globalny
variables = savedVariables;
arrays = savedArrays;
matrices = savedMatrices;
structVars = savedStructVars;
```

**Dlaczego nie stos zakresów?** Język MyLang nie ma zagnieżdżonych zakresów — funkcje są wyłącznie na poziomie globalnym, nie można definiować funkcji wewnątrz funkcji. Wystarczy model "zapisz/wyczyść/przywróć".

**Dlaczego zmienne globalne nie są widoczne wewnątrz funkcji?** Wszystkie zmienne (w tym "globalne" instrukcje na poziomie `main`) są alokowane na stosie `@main`. Funkcje użytkownika mają własne stosy — nie mają dostępu do stosu `@main`. To celowe ograniczenie projektu.

### Rejestracja funkcji przed odwiedzeniem ciała

```java
// visitFuncDeclRule — PRZED visit(body):
FuncInfo fi = new FuncInfo(name, returnType, paramNames, paramTypes);
functions.put(name, fi);

// ...dopiero teraz:
visit(ctx.block());
```

Dzięki temu funkcja rekurencyjna może wywołać siebie: `fib(n-1)` szuka `fib` w mapie `functions`, która już istnieje.

### Implicitny dostęp do pól wewnątrz metod klasy

Wewnątrz metody klasy `currentClassName != null`. Gdy `visitAssign` lub `visitIdRef` nie znajdzie zmiennej w `variables`, sprawdza pola klasy:

```java
if (currentClassName != null) {
    StructInfo si = structs.get(currentClassName);
    int idx = si.fieldIndex(id);
    if (idx >= 0) {
        // Dostęp przez self: %self_ptr = load %ClassName*, %ClassName** %self_alloca
        // %field_ptr = getelementptr %ClassName, %ClassName* %self_ptr, i32 0, i32 idx
        // load/store na %field_ptr
    }
}
```

Umożliwia to pisanie `top = top + 1` zamiast `self.top = self.top + 1` wewnątrz metody.

### isDeclared — sprawdzenie deklaracji we wszystkich mapach

```java
private boolean isDeclared(String id) {
    return variables.containsKey(id)
        || arrays.containsKey(id)
        || matrices.containsKey(id)
        || structVars.containsKey(id);
}
```

### buildArgs — pomocnik budowania listy argumentów

```java
private List<Value> buildArgs(FuncInfo fi, List<MyLangParser.ExprContext> argCtxs) {
    // Sprawdź liczbę argumentów
    // Odwiedź każde wyrażenie → Value
    // Coerce do oczekiwanego typu parametru
    // Zwróć listę Value
}
```

### Dlaczego visitor, a nie listener?

Visitor jest konieczny, bo wyrażenia muszą propagować wartości (`Value`) w górę drzewa — `visitAddOp` potrzebuje wyników lewego i prawego poddrzewa. Listener nie zapewnia zwracanej wartości z węzła.

### Generowanie IR jednocześnie z analizą

Kompilator jest jednoprzebiegowy. Błędy semantyczne: `errors++` + print na stderr, kompilacja kontynuuje. IR drukowany tylko gdy `errors == 0`.

---

## 7. Punkt wejścia

### Main.java

`Main.java` odpowiada za:
1. Odczyt pliku źródłowego.
2. Uruchomienie lexera i parsera z własnym `ErrorListener`.
3. Sprawdzenie błędów składniowych — jeśli są, przerwanie przed wizytacją.
4. Uruchomienie `LLVMActions.visit(tree)`.
5. Sprawdzenie błędów semantycznych.

### ErrorListener.java

Zastępuje domyślny `ConsoleErrorListener` ANTLR4. Formatuje komunikaty z numerem linii i opisem nieoczekiwanego tokenu. Metoda `hasErrors()` pozwala `Main.java` sprawdzić błędy przed kontynuacją.

---

## 8. Kluczowe decyzje projektowe

### Dlaczego ANTLR4?

ANTLR4 generuje parser LL(*) — działa dla większości praktycznych gramatyk bez lewej rekursji. Eliminuje ręczne pisanie parsera, generuje visitor i listener out-of-the-box, jest aktywnie utrzymywany. Alternatywy: Bison+Flex (C, inny język), JavaCC (stary), ręczny RD-parser (więcej kodu).

### Dlaczego LLVM IR jako cel?

LLVM IR jest platformowo niezależny. `lli` umożliwia natychmiastowe wykonanie. `opt` może opcjonalnie poprawić kod. Alternatywy (JVM bytecode, kod C, kod maszynowy) są bardziej skomplikowane lub mniej przenośne.

### Dlaczego brak AST?

Projekt jest wystarczająco mały, żeby visitor działał bezpośrednio na CST generowanym przez ANTLR. Budowanie oddzielnego AST zwiększyłoby nakład pracy bez korzyści dla jednoprzebiegowego kompilatora.

### Dlaczego funkcje są deklarowane przed ciałem?

Rejestracja `FuncInfo` przed odwiedzeniem ciała umożliwia rekurencję bez deklaracji forward. W C konieczne jest `int fib(int n);` przed definicją — tutaj kompilator sam obsługuje ten przypadek.

### Dlaczego brak dziedziczenia klas?

Zakres projektu. Klasy mają metody i pola, ale nie dziedziczą po sobie. Upraszcza to resolucję metod (zawsze szukamy w konkretnej klasie, nie w hierarchii) i generowanie LLVM IR (nie ma vtable, nie ma wskaźnika `vptr`).

---

## 9. Szczegóły LLVM IR

### Forma SSA

LLVM IR wymaga formy SSA: każdy rejestr (`%N`) jest przypisywany dokładnie jeden raz. Zmienne są przechowywane przez `alloca`/`store`/`load`. Optymalizator LLVM `mem2reg` promuje `alloca` do prawdziwych rejestrów SSA.

### Format strings jako stałe globalne

```llvm
@fmt_i32_p = constant [4 x i8] c"%d\0A\00"
@fmt_f64_p = constant [4 x i8] c"%f\0A\00"
@fmt_str_r = constant [6 x i8] c"%255s\00"
```

`\0A` = `\n`, `\00` = null terminator. Długość tablicy obejmuje null terminator.

### Float wymaga fpext przed printf

`printf` jest funkcją variadyczną. ABI x86-64 wymaga rozszerzenia `float → double`:

```llvm
%ext = fpext float %f to double
call @printf(..., double %ext)
```

### Deklaracje zewnętrznych funkcji

```llvm
declare i32 @printf(i8*, ...)
declare i32 @__isoc99_scanf(i8*, ...)
declare i8* @strcpy(i8*, i8*)
```

`__isoc99_scanf` zamiast `scanf` — na systemach Linux z glibc `scanf %lf` mapuje na `__isoc99_scanf`.

### Definicje typów strukturalnych

```llvm
%Vec2 = type { double, double }
%Point = type { i32, i32 }
%Stack = type { i32, i32, i32, i32 }
```

Typy muszą być zdefiniowane przed użyciem w funkcjach. Dlatego `structs_text` pojawia się przed `funcs_text` w `generate()`.

---

## 10. Tablice 1D

### Deklaracja

```antlr
typeName '[' INT ']' ID             #arrayDecl         ; int[5] tab
typeName '[' INT DOTDOT INT ']' ID  #rangedArrayDecl   ; int[1..3] ranged
```

W LLVM IR obie deklaracje generują alokację o fizycznym rozmiarze:

```llvm
%tab    = alloca [5 x i32]
%ranged = alloca [3 x i32]   ; 3 = 3 - 1 + 1
```

### Dostęp do elementu — getelementptr

```llvm
%ptr = getelementptr inbounds [5 x i32], [5 x i32]* %tab, i32 0, i32 %idx
```

Pierwszy indeks `i32 0` de-referencjonuje wskaźnik do tablicy; drugi `i32 %idx` wskazuje element.

### Przeliczenie indeksu — adjustIndex

Dla tablic z zakresem (np. `int[1..3]`) kompilator odejmuje `startIndex`:

```llvm
%physical_idx = sub i32 %user_idx, 1
```

`adjustIndex` emituje tę instrukcję tylko gdy `startIndex != 0`.

### Wypisanie całej tablicy

`print arr` → `visitPrintIdStat` → `LLVMGenerator.printArrayAll`. Pętla Java iteruje compile-time po rozmiarze i emituje GEP + load + printf dla każdego elementu. Brak pętli w LLVM IR.

---

## 11. Tablice 2D — macierze

### Deklaracja

```antlr
typeName '[' INT ']' '[' INT ']' ID   #matrixDecl    ; int[3][4] mat
```

LLVM IR: tablica tablic (nested arrays):

```llvm
%mat = alloca [3 x [4 x i32]]
```

### Dostęp do elementu — dwupoziomowy GEP

Element `mat[row][col]` wymaga dwóch poziomów indeksowania:

```llvm
; Krok 1: wskaźnik do wiersza
%row_ptr = getelementptr inbounds [3 x [4 x i32]], [3 x [4 x i32]]* %mat, i32 0, i32 %row_reg

; Krok 2: wskaźnik do elementu w wierszu
%elem_ptr = getelementptr inbounds [4 x i32], [4 x i32]* %row_ptr, i32 0, i32 %col_reg
```

### Wypisanie całej macierzy

`print mat` → `visitPrintIdStat` → `LLVMGenerator.printMatrixAll`. Podwójna pętla Java (compile-time) generuje GEP + load + printf dla każdego elementu `[i][j]`.

### Macierze jako zmienne lokalne funkcji

Macierz może być deklarowana wewnątrz funkcji — alokacja i wszystkie dostępy działają tak samo jak w `@main`, bo `alloca` zawsze alokuje na bieżącym stosie wywołania.

```
fun int matTrace() {
    int[3][3] lm
    lm[0][0] = 1
    ...
    return lm[0][0] + lm[1][1] + lm[2][2]
}
```

---

## 12. Short-circuit evaluation — OR i AND

### Problem

`a OR b`: jeśli `a = true`, `b` nie jest obliczane. `a AND b`: jeśli `a = false`, `b` jest pomijane.

### Implementacja — OR

```
oblicz left                        ; left w %L
condBr %L, or_true, or_eval        ; blockTerminated = true
or_eval:                           ; reset blockTerminated
    oblicz right                   ; right w %R
    br or_end
or_true:
    br or_end
or_end:
    %result = phi i1 [%R, or_eval], [true, or_true]
```

### Implementacja — AND

```
oblicz left
condBr %L, and_eval, and_false
and_eval:
    oblicz right
    br and_end
and_false:
    br and_end
and_end:
    %result = phi i1 [%R, and_eval], [false, and_false]
```

### Dlaczego phi, a nie load/store?

`phi` jest idiomatyczne dla SSA i bardziej efektywne — nie wymaga dostępu do pamięci.

### Śledzenie currentBlock

`phi` wymaga podania nazwy bloku-źródła dla każdej wartości. `emitLabel` aktualizuje `currentBlock`; `visitOrOp`/`visitAndOp` zapisują `evalEndBlock = LLVMGenerator.currentBlock` po odwiedzeniu prawego poddrzewa.

### XOR nie ma short-circuit

XOR wymaga obu wartości: `%result = xor i1 %left, %right`.

---

## 13. Instrukcje sterujące

### if / else if / else

Gramatyka:

```antlr
'if' '(' expr ')' block
    ('else' 'if' '(' expr ')' block)*
    ('else' block)?
#ifStat
```

Generowany wzorzec LLVM IR dla `if (c1) B1 else if (c2) B2 else B3`:

```
oblicz c1
condBr c1, if_true_0, if_false_0   → blockTerminated

if_true_0:                          ← reset
    [kod B1]
    br if_end

if_false_0:                         ← reset
    oblicz c2
    condBr c2, if_true_1, if_false_1

if_true_1:                          ← reset
    [kod B2]
    br if_end

if_false_1:                         ← reset
    [kod B3]
    br if_end

if_end:                             ← reset
```

Etykiety generowane dynamicznie: `if_true_N`, `if_false_N`, `if_end` (N = unikalny licznik `labelCount`). `br if_end` jest emitowane tylko jeśli `!blockTerminated` (ciało mogło zawierać `return`).

### while

```antlr
'while' '(' expr ')' block   #whileStat
```

Wzorzec LLVM IR:

```
br while_cond

while_cond:                         ← reset
    oblicz warunek
    condBr cond, while_body, while_end

while_body:                         ← reset
    [kod ciała]
    br while_cond                  (tylko jeśli !blockTerminated)

while_end:                          ← reset
```

Skok `br while_cond` na początku gwarantuje, że warunek jest sprawdzany przed każdą iteracją, w tym przed pierwszą. `br while_cond` na końcu ciała jest emitowane tylko jeśli `!blockTerminated`.

### for

```antlr
'for' ID '=' expr 'to' expr block   #forStat
```

Semantyka: iteracja od `start` do `end - 1` włącznie (zakres prawostronnie otwarty).

Wzorzec LLVM IR dla `for k = 1 to 11`:

```
store i32 %start, i32* %k          ; k = start
br for_cond

for_cond:                           ← reset
    %kval = load i32, i32* %k
    %end  = oblicz end_expr
    %cond = icmp slt i32 %kval, %end   ; k < end
    condBr %cond, for_body, for_end

for_body:                           ← reset
    [kod ciała]
    (jeśli !blockTerminated):
        %kval2 = load i32, i32* %k
        %kinc  = add i32 %kval2, 1
        store i32 %kinc, i32* %k
    br for_cond

for_end:                            ← reset
```

**Dlaczego `icmp slt` (signed less than)?** Indeks pętli jest typem `int` (ze znakiem). `slt` porównuje znakowe liczby całkowite.

**Dlaczego inkrementacja pomijana gdy `blockTerminated`?** Jeśli ciało zawierało `return`, dalsze emitowanie kodu (ładowania, dodawania, store) generowałoby unreachable instrukcje, które LLVM odrzuca.

**Zmienna pętli zadeklarowana automatycznie:** jeśli `k` nie istnieje w zakresie, `visitForStat` automatycznie deklaruje ją jako `int k` (alloca + entry w `variables`).

---

## 14. Funkcje użytkownika

### Deklaracja

```antlr
fun int max(int x, int y) {
    if (x > y) { return x }
    return y
}
```

Odpowiednik LLVM IR:

```llvm
define i32 @max(i32 %x_arg, i32 %y_arg) {
entry:
    %x = alloca i32
    store i32 %x_arg, i32* %x
    %y = alloca i32
    store i32 %y_arg, i32* %y

    ; ... ciało ...
    ret i32 %result
}
```

Parametry `x_arg`, `y_arg` są natychmiast kopiowane do zmiennych lokalnych `alloca` (`x`, `y`). Dzięki temu parametry zachowują się jak zwykłe zmienne lokalne (można je modyfikować).

### Wywołanie

```antlr
ID '(' argList? ')'   #funcCallExpr
ID '(' argList? ')'   #funcCallStat
```

`visitFuncCallExpr` i `visitFuncCallStat` wyszukują `FuncInfo` w mapie `functions`, budują argumenty przez `buildArgs`, emitują:

```llvm
%result = call i32 @max(i32 %a_val, i32 %b_val)
```

Dla funkcji `void` nie ma rejestru wynikowego: `call void @printSep()`.

### Rekurencja

Działa dzięki zarejestrowaniu `FuncInfo` przed odwiedzeniem ciała:

```
fun int fib(int n) {
    if (n <= 1) { return n }
    return fib(n - 1) + fib(n - 2)    ; fib znalezione w functions
}
```

### Funkcje void

```
fun void printSep() {
    print 0
}
```

LLVM IR: `define void @printSep()`. `endFunction` emituje `ret void` jeśli `!blockTerminated`. `visitReturnStat` dla `void`: `ret void`.

### Zakres zmiennych

Zmienne zadeklarowane wewnątrz funkcji są lokalne — nie są widoczne poza nią. Zmienne z zakresu `@main` nie są widoczne wewnątrz funkcji (model prostego stosu save/restore opisany w sekcji 6).

---

## 15. Struktury i klasy

### Struct — rekord danych

```
struct Vec2 {
    real x
    real y
}

Vec2 v
v.x = 3.0
v.y = 4.0
real len_sq = v.x * v.x + v.y * v.y
```

LLVM IR:

```llvm
%Vec2 = type { double, double }

; deklaracja zmiennej:
%v = alloca %Vec2

; zapis pola v.x (pole index 0):
%fptr = getelementptr %Vec2, %Vec2* %v, i32 0, i32 0
store double 3.0, double* %fptr

; odczyt pola v.x:
%fptr2 = getelementptr %Vec2, %Vec2* %v, i32 0, i32 0
%vx = load double, double* %fptr2
```

`structVars` mapuje nazwę zmiennej → nazwę typu: `structVars.put("v", "Vec2")`. `visitFieldAssign` i `visitFieldRef` korzystają z `structVars` + `structs` by uzyskać indeks pola i typ.

### Class — struct z metodami

```
class Stack {
    int val0
    int val1
    int val2
    int top

    fun void init() { top = 0 }
    fun void push(int v) { ... }
    fun int peek() { ... }
    fun int size() { return top }
}

Stack stk
stk.init()
stk.push(10)
print stk.peek()
```

LLVM IR dla klasy:

```llvm
%Stack = type { i32, i32, i32, i32 }

; Metody generowane jako zwykłe funkcje z ukrytym pierwszym parametrem %Stack* %self:
define void @Stack_init(%Stack* %self_arg) {
entry:
    %self = alloca %Stack*
    store %Stack* %self_arg, %Stack** %self
    ; dostęp do top (pole index 3):
    %sptr = load %Stack*, %Stack** %self
    %fptr = getelementptr %Stack, %Stack* %sptr, i32 0, i32 3
    store i32 0, i32* %fptr
    ret void
}
```

Wywołanie `stk.init()` → `call void @Stack_init(%Stack* %stk_ptr)`.

### Mangling nazw metod

Metoda `init` klasy `Stack` generuje LLVM-ową funkcję `@Stack_init`. Mangling: `@ClassName_methodName`. Zapobiega kolizjom nazw przy klasach mających metody o tych samych nazwach.

### Implicitny self

Wewnątrz metody `currentClassName = "Stack"`. Gdy `visitAssign` lub `visitIdRef` natrafia na `top` (nieznane w `variables`), sprawdza `structs.get("Stack").fieldIndex("top")` → 3, i generuje GEP przez wskaźnik `self`. Programista pisze `top = 0` zamiast `self.top = 0`.

### Rejestracja metod w StructInfo

`visitClassDeclRule` najpierw zbiera wszystkie pola (iteracja po `ClassMemberField`), potem przetwarza metody (iteracja po `ClassMemberMethod`). Każda metoda jest wpisana do `si.methods` i natychmiast kompilowana przez `visitMethodDecl`.

---

## 16. Konwersja typów

### Dwa mechanizmy: coerce i cast

**`coerce`** — niejawna konwersja wykonywana automatycznie przy przypisaniach i inicjalizacjach:

- `int → float32` (sitofp)
- `int → double` (sitofp)
- `float32 → double` (fpext)
- `double → float32` (fptrunc — zawężenie, uwaga na utratę precyzji)

**`cast`** — jawna konwersja przez `(typ) wyrażenie`:

- `float → int` (fptosi — obcięcie, nie zaokrąglenie)
- `int → bool` (icmp ne 0)
- `bool → int` (zext)
- `bool → float` (zext + sitofp)

### Dlaczego rozróżnienie?

Jawne rzutowanie sygnalizuje świadomość potencjalnej utraty precyzji. Niejawna konwersja działa tylko dla poszerzeń.

### unifyNumeric — ujednolicenie przed operacją binarną

```
int + float32    → float32 + float32
int + double     → double + double
float32 + double → double + double
```

Dzięki temu `int x = 2; real y = 1.5; real z = x + y` działa bez jawnego rzutowania.

---

## 17. Wejście-Wyjście

### printf

| Metoda | Format | Typ LLVM |
|---|---|---|
| `printf_i32` | `%d\n` | `i32` |
| `printf_double` | `%f\n` | `double` |
| `printf_float` | `%f\n` | `double` (po fpext) |
| `printf_bool` | `%d\n` | `i32` (po zext) |
| `printf_string` | `%s\n` | `i8*` |

### scanf

Wczytywanie analogiczne. Format `%255s` dla stringów ogranicza długość do 255 znaków. Każda zmienna `string` ma bufor `[256 x i8]` na stosie. Wczytywanie bool: wczytanie jako `i32`, konwersja `icmp ne 0` → `i1`.

---

## 18. Typowe pytania obrończe

**Dlaczego wybrałeś ANTLR4 do parsowania?**  
ANTLR4 generuje parser LL(*) z automatyczną obsługą niejednoznaczności. Eliminuje ręczne pisanie parsera, generuje visitor i listener out-of-the-box, produkuje czytelny kod Java. Alternatywy (Bison, ręczny RD-parser) wymagają znacznie więcej pracy.

**Dlaczego kod IR jest budowany przez konkatenację stringów?**  
LLVM IR to format tekstowy — cel to plik `.ll`. Budowanie przez stringi jest najprostszym podejściem wystarczającym dla jednoetapowego kompilatora. Alternatywą byłoby LLVM API (przez JNI/LLVM4J), znacznie bardziej skomplikowane, bez mierzalnej korzyści przy tej skali.

**Co to jest SSA i dlaczego LLVM tego wymaga?**  
SSA (Static Single Assignment) — każdy rejestr jest przypisywany dokładnie raz. Upraszcza analizę przepływu danych i umożliwia optymalizacje (stała propagacja, eliminacja martwego kodu). Obsługujemy SSA przez `alloca`/`load`/`store`; `mem2reg` automatycznie promuje to do prawdziwego SSA.

**Jak działają bloki podstawowe i terminator?**  
Blok podstawowy to sekwencja instrukcji bez skoków wewnątrz, kończąca się dokładnie jednym terminatorem (`ret`, `br`, `condBr`). LLVM weryfikuje tę właściwość. Flaga `blockTerminated` w generatorze zapobiega emitowaniu duplikatów terminatorów — gdy gałąź `if` kończy się `return`, generator wie, że nie może już emitować `br if_end`.

**Jak obsługujesz rekurencję funkcji?**  
`FuncInfo` jest rejestrowana w mapie `functions` **przed** odwiedzeniem ciała funkcji. Dzięki temu gdy visitor napotka wywołanie `fib(n-1)` wewnątrz definicji `fib`, znajdzie ją w mapie i wygeneruje poprawne wywołanie LLVM. W C odpowiednikiem jest deklaracja forward.

**Jak działają klasy i metody w LLVM IR?**  
Klasa jest typem strukturalnym (`%Stack = type { i32, i32, ... }`). Metody są funkcjami z ukrytym pierwszym argumentem `%Stack* %self`. Wywołanie `stk.push(10)` tłumaczy się na `call void @Stack_push(%Stack* %stk_ptr, i32 10)`. Nie ma vtable ani dziedziczenia — resolucja metody jest statyczna (w czasie kompilacji).

**Dlaczego zmienne globalne nie są widoczne wewnątrz funkcji?**  
"Globalne" zmienne (`@main`) są alokowane na stosie `@main`. Funkcje użytkownika mają własne stosy wywołań i nie mają dostępu do stosu `@main`. To celowe ograniczenie projektu (upraszcza model zakresu). Prawdziwe zmienne globalne wymagałyby alokacji na poziomie modułu (`@global = global i32 0`).

**Jak obsługujesz tablice 2D w LLVM IR?**  
Macierz `int[R][C]` jest alokowana jako `alloca [R x [C x i32]]` — tablica tablic. Dostęp do elementu `mat[row][col]` wymaga dwóch poziomów GEP: pierwszy indeksuje wiersz (typ `[C x i32]`), drugi element w wierszu. LLVM wymaga podania pełnych typów na każdym poziomie.

**Dlaczego `for k = 1 to 11` iteruje do 10 (nie 11)?**  
Pętla `for` używa warunku `icmp slt k, end` (k < end), więc zakres jest prawostronnie otwarty: `[start, end)`. `for k = 1 to 11` wykonuje ciało dla k = 1, 2, ..., 10. To konwencja analogiczna do `range(1, 11)` w Pythonie lub `for(k=1; k<11; k++)` w C.

**Co się dzieje gdy wystąpi błąd semantyczny?**  
`semanticError` inkrementuje licznik `errors` i drukuje komunikat na stderr. Kompilacja kontynuuje (visitor nie przerywa), co pozwala znaleźć wiele błędów naraz. Po zakończeniu `visitProg` sprawdza `errors == 0` i tylko wtedy drukuje IR. `Main.java` sprawdza `actions.hasErrors()` i kończy z kodem wyjścia 1.

**Dlaczego tablice mają stały rozmiar, a nie dynamiczny?**  
Stały rozmiar znany w czasie kompilacji pozwala na statyczną alokację na stosie (`alloca [N x T]`) i compile-time unrolling drukowania. Dynamiczne tablice wymagałyby `malloc`/`free`, zarządzania pamięcią i runtime'owych sprawdzeń zakresu.

**Czy jest sprawdzanie zakresu indeksów?**  
Nie w czasie wykonania. Kompilator sprawdza typ indeksu (musi być `int`), ale nie sprawdza wartości. Wyjście poza zakres daje niezdefiniowane zachowanie, jak w C. Dodanie sprawdzania wymagałoby emisji kodu porównania i warunkowego skoku do obsługi błędu w czasie wykonania.

**Dlaczego `__isoc99_scanf` zamiast `scanf`?**  
Na systemach Linux z glibc (wersja > 2.2.5), `scanf` z formatem `%lf` mapuje na `__isoc99_scanf` — eksportowaną przez glibc dla standardu C99. LLVM IR deklarując `__isoc99_scanf` unika problemów z linkowaniem.

**Jak działa print dla całej tablicy 2D?**  
`print mat` trafia do `visitPrintIdStat`, który wykrywa macierz w mapie `matrices`. Wywołuje `LLVMGenerator.printMatrixAll` — podwójna pętla Java (czas kompilacji) generuje osobne instrukcje `GEP wiersz` + `GEP element` + `load` + `printf` dla każdego `[i][j]`. Rozmiar macierzy znany w czasie kompilacji, więc unrolling jest możliwy.

**Jak działa implicitny self w metodach klasy?**  
Gdy `currentClassName != null` i `visitAssign`/`visitIdRef` nie znajdzie zmiennej w lokalnej mapie `variables`, sprawdza pola klasy przez `structs.get(currentClassName).fieldIndex(id)`. Jeśli znajdzie pasujące pole, generuje GEP przez wskaźnik `self` zamiast sygnalizować błąd. Umożliwia to pisanie `top = 0` zamiast `self.top = 0` wewnątrz metody.
