# Kompendium wiedzy — Kompilator MyLang

## Spis treści

1. [Ogólna architektura — potok kompilacji](#1-ogólna-architektura)
2. [Gramatyka — MyLang.g4](#2-gramatyka--mylang-g4)
3. [System typów — VarType.java i Value.java](#3-system-typów)
4. [Metadane tablic — ArrayInfo.java](#4-metadane-tablic)
5. [Generowanie kodu — LLVMGenerator.java](#5-generowanie-kodu--llvmgenerator)
6. [Analiza semantyczna — LLVMActions.java](#6-analiza-semantyczna--llvmactions)
7. [Punkt wejścia — Main.java i ErrorListener.java](#7-punkt-wejścia)
8. [Kluczowe decyzje projektowe](#8-kluczowe-decyzje-projektowe)
9. [Szczegóły LLVM IR](#9-szczegóły-llvm-ir)
10. [Tablice — implementacja i zakres indeksów](#10-tablice)
11. [Short-circuit evaluation — OR i AND](#11-short-circuit-evaluation)
12. [System konwersji typów — coerce vs cast](#12-konwersja-typów)
13. [Obsługa wejścia-wyjścia — printf i scanf](#13-wejście-wyjście)
14. [Typowe pytania obrończe i odpowiedzi](#14-typowe-pytania-obrończe)

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

---

## 2. Gramatyka — MyLang.g4

### Co robi

Plik `MyLang.g4` jest specyfikacją języka MyLang w notacji ANTLR4. Zawiera reguły leksykalne (tokeny) i składniowe (parser rules). Na jego podstawie ANTLR4 generuje lexer i parser oraz interfejs visitora.

### Reguła startowa

```antlr
prog: (stat? NEWLINE)* EOF ;
```

Program to sekwencja linii oddzielonych znakami nowej linii. Każda linia może być pusta (`stat?` — stat opcjonalne). `NEWLINE` jest tokenem jawnym, nie ignorowanym — to separator instrukcji. Dzięki temu składnia języka jest wrażliwa na linie, jak np. Python.

**Dlaczego NEWLINE zamiast `;`?** Projektowo język ma prostą składnię bez terminator. Możliwą alternatywą było `';'` jako separator (jak w C) albo opcjonalny separator (jak w JavaScript). Wybrano NEWLINE, bo jest czytelniejszy dla prostych skryptów.

### Hierarchia wyrażeń

Gramatyka enkoduje pierwszeństwo operatorów przez zagnieżdżenie reguł. Im głębiej w drzewie, tym wyższy priorytet:

```
orExpr < xorExpr < andExpr < notExpr < cmpExpr < addExpr < mulExpr < castExpr < unaryExpr < primaryExpr
```

Każdy poziom deleguje do następnego przez reguły "pass" (np. `#orPass`). To standardowa technika w gramatykach LL, eliminująca niejednoznaczności bez definiowania priorytetów w inny sposób.

**Dlaczego named alternatives (`#orOp`, `#orPass`)?** ANTLR4 generuje osobną metodę `visitXxx` dla każdej etykiety. Bez nich wszystkie alternatywy w regule trafiają do jednej metody i trzeba by ręcznie rozróżniać przypadki przez `ctx.op` lub sprawdzanie podtypów. Etykiety dają czysty, typowany visitor.

### Kolejność reguł stat — dlaczego ma znaczenie

ANTLR4 parsuje reguły deterministycznie (LL). Kolejność alternatyw w `stat` musi być:

- `rangedArrayDecl` przed `arrayDecl` — oba zaczynają się od `typeName '['`; ANTLR musi zobaczyć `INT DOTDOT` zanim wybierze regułę.
- `arrayAssign` przed `assign` — `ID '[' ...` vs `ID '=' ...`; drugi token rozróżnia przypadki.
- `readArrayStat` przed `readStat` — obecność `'['` po ID.
- `printIdStat` przed `printStat` — `print x` mogłoby pasować do `printStat` jeśli `x` zostanie sparsowane jako wyrażenie; `printIdStat` wymusza dopasowanie do samego ID.

### Token DOTDOT

```antlr
DOTDOT: '..';
```

Musi być zdefiniowany przed tokenem `REAL`. Gdyby `REAL` dopuszczał `1.` (bez cyfr po kropce), sekwencja `1..5` zostałaby ztokenizowana jako `REAL("1.")` + `DOT` + `INT("5")` — błąd parsowania. `REAL` wymaga cyfr po obu stronach kropki (`[0-9]+ '.' [0-9]+`), więc `1..5` daje `INT DOTDOT INT`.

---

## 3. System typów

### VarType.java

`VarType` to enum z wartościami: `INT`, `REAL`, `FLOAT32`, `FLOAT64`, `BOOL`, `STRING`, `UNKNOWN`.

Kluczowe metody:

- `llvm()` — zwraca odpowiedni typ LLVM IR (`i32`, `double`, `float`, `i1`, `i8*`).
- `isFloat()` — `true` dla `REAL`, `FLOAT32`, `FLOAT64`.
- `isNumeric()` — `true` dla `INT` i wszystkich float.

**Dlaczego `REAL` i `FLOAT64` mapują na ten sam typ `double`?** W LLVM IR nie ma różnicy między nimi na poziomie instrukcji — oba są 64-bitowe. Rozróżnienie istnieje tylko na poziomie języka MyLang, by programista miał dwa aliasy na ten sam typ. Sprawia to, że `real + float64` nie wymaga konwersji.

**Dlaczego enum, a nie klasy?** Typy są skończonym, zamkniętym zbiorem. Enum gwarantuje, że nie da się dodać nieznanego typu przez przypadek, jest bezpiecznie porównywalny przez `==` i działa sprawnie w `switch`.

### Value.java

```java
public class Value {
    public final String name;  // rejestr LLVM lub literał, np. "%3" lub "42"
    public final VarType type; // typ wartości
}
```

`Value` to para (rejestr LLVM, typ). Każde wyrażenie zwraca `Value` — visitor propaguje wartości w górę drzewa.

**Dlaczego niemodyfikowalny (final)?** LLVM IR jest w formie SSA (Static Single Assignment) — każdy rejestr jest przypisywany raz. Niemutowalność `Value` odzwierciedla tę właściwość: raz wyliczona wartość nigdy się nie zmienia.

---

## 4. Metadane tablic

### ArrayInfo.java

```java
public class ArrayInfo {
    public final VarType elementType;
    public final int startIndex;
    public final int endIndex;
    public final int size;
}
```

`ArrayInfo` przechowuje wszystko, co kompilator musi wiedzieć o tablicy w czasie kompilacji: typ elementu, zakres indeksów użytkownika i faktyczny rozmiar fizyczny (`endIndex - startIndex + 1`).

Dla tablicy `int[5] tab` — `startIndex=0`, `endIndex=4`, `size=5`.  
Dla tablicy `int[1..3] ranged` — `startIndex=1`, `endIndex=3`, `size=3`.

Przy dostępie do elementu kompilator przelicza indeks użytkownika na indeks tablicy przez odjęcie `startIndex`. To odbywa się w metodzie `adjustIndex` w `LLVMActions`.

---

## 5. Generowanie kodu — LLVMGenerator

### Co robi

`LLVMGenerator` to klasa statyczna (wszystkie pola i metody `static`) pełniąca rolę buildera LLVM IR. Zamiast budować AST pośrednie, akumuluje kod IR bezpośrednio do dwóch stringów:

- `header_text` — stałe globalne (literały stringów, format strings).
- `main_text` — instrukcje wewnątrz funkcji `@main`.

Na końcu metoda `generate()` skleja wszystko w kompletny plik `.ll`.

### Kluczowe pola

| Pole | Opis |
|---|---|
| `reg` | globalny licznik rejestrów SSA; każde `nextReg()` zwraca `%N` i inkrementuje |
| `labelCount` | licznik etykiet bloków; używany do generowania unikalnych nazw |
| `readBufCount` | licznik tymczasowych buforów `read bool` |
| `strLitCount` | licznik stałych stringów globalnych |
| `currentBlock` | nazwa aktualnie otwartego bloku — potrzebna dla `phi` |

### Dlaczego klasa statyczna, a nie instancja?

Kompilator obsługuje jeden program na raz. Klasa statyczna eliminuje konieczność przekazywania referencji do generatora przez cały visitor. To uproszczenie akceptowalne przy jednorazowym użyciu. Gdyby kompilator miał obsługiwać wiele plików równolegle lub wielokrotnie w tym samym procesie, należałoby przerobić na instancję — ale w tym projekcie nie ma takiej potrzeby.

### alloca / load / store — wzorzec zmiennych

Każda zmienna `x` typu `T` jest reprezentowana jako slot na stosie:

```llvm
%x = alloca i32        ; rezerwacja miejsca
store i32 5, i32* %x   ; zapis wartości
%1 = load i32, i32* %x ; odczyt wartości → do rejestru %1
```

**Dlaczego tak, a nie przez bezpośrednie wartości?** LLVM IR jest w SSA — każdy rejestr może być przypisany tylko raz. Zmienne wymagają możliwości wielokrotnego przypisania (`x = 1`, potem `x = 2`). Rozwiązanie przez `alloca` pozwala emulować mutable variables bez skomplikowanego analizowania przepływu sterowania i wstawiania `phi` dla każdej zmiennej. To podejście stosuje większość front-endów (Clang też tak robi) — optymalizator LLVM (`mem2reg`) sam promuje `alloca` do rejestrów SSA.

---

## 6. Analiza semantyczna — LLVMActions

### Co robi

`LLVMActions` rozszerza `MyLangBaseVisitor<Value>`. Visitor przechodzi po drzewie składniowym i dla każdego węzła:

1. Sprawdza poprawność semantyczną (typy, deklaracje).
2. Wywołuje odpowiednie metody `LLVMGenerator`, generując kod IR.
3. Zwraca `Value` reprezentujące wynik wyrażenia (rejestr LLVM + typ).

### Pola stanu

```java
private final Map<String, VarType> variables;  // zmienne skalarne: nazwa → typ
private final Map<String, ArrayInfo> arrays;   // tablice: nazwa → metadane
private int errors;                             // licznik błędów semantycznych
```

Są to oddzielne mapy, bo operacje na zmiennych i tablicach różnią się (tablica nie może być użyta jak zmienna skalarna i odwrotnie). `isDeclared(id)` sprawdza oba.

### Dlaczego visitor, a nie listener?

ANTLR4 oferuje dwa wzorce: **listener** (automatyczne przechodzenie, brak zwracanej wartości) i **visitor** (manualne przechodzenie, zwraca wartość z każdego węzła). Visitor jest tu konieczny, bo wyrażenia muszą propagować wartości (`Value`) w górę drzewa — np. `visitAddOp` potrzebuje wyników lewego i prawego poddrzewa, by wygenerować instrukcję `add`. Listener tego nie zapewnia bez dodatkowego stosu.

### Generowanie IR jednocześnie z analizą

Kompilator jest jednoprzebiegowy — nie buduje oddzielnego AST ani tabeli symboli poza mapami zmiennych. Zalety: prostota, mniejsza ilość kodu. Wada: błędy semantyczne mogą powodować częściowe wygenerowanie IR. Dlatego kompilator zbiera błędy przez `errors++` i drukuje IR dopiero gdy `errors == 0` (sprawdzane w `visitProg`).

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

Zastępuje domyślny `ConsoleErrorListener` ANTLR4, który drukuje brzydkie ślady. `ErrorListener` formatuje komunikaty po polsku z numerem linii i opisem nieoczekiwanego tokenu. Metoda `hasErrors()` pozwala `Main.java` sprawdzić, czy wystąpiły błędy przed kontynuacją.

**Dlaczego osobny plik?** Zasada pojedynczej odpowiedzialności. `Main.java` koordynuje potok; `ErrorListener.java` odpowiada wyłącznie za formatowanie komunikatów błędów.

---

## 8. Kluczowe decyzje projektowe

### Dlaczego ANTLR4?

ANTLR4 generuje parser LL(*) — działa dla większości praktycznych gramatyk bez lewej rekursji. Alternatywy:

- **Ręcznie pisany parser rekurencyjny z zejściem** — daje pełną kontrolę, ale wymaga znacznie więcej kodu dla obsługi pierwszeństwa operatorów i błędów.
- **JavaCC / CUP** — starsze narzędzia, mniejsza społeczność, trudniejsza integracja z nowoczesnym Java.
- **BISON + Flex** (C) — klasyczne narzędzia, ale inny język implementacji.

ANTLR4 generuje czytelny kod Java, ma wsparcie dla visitorów i listenerów out-of-the-box oraz jest aktywnie utrzymywany.

### Dlaczego LLVM IR jako cel?

- LLVM IR jest platformowo niezależny — ten sam `.ll` działa na x86, ARM i innych.
- `lli` umożliwia natychmiastowe wykonanie bez osobnej kompilacji do kodu maszynowego.
- LLVM optymalizator (`opt`) może opcjonalnie poprawić wygenerowany kod.
- Alternatywą byłoby generowanie kodu JVM (bytecode), kodu C lub kodu maszynowego bezpośrednio — każda z tych opcji jest bardziej skomplikowana lub mniej przenośna.

### Dlaczego brak AST?

Projekt jest wystarczająco mały, żeby visitor działał bezpośrednio na CST (Concrete Syntax Tree) generowanym przez ANTLR. Budowanie oddzielnego AST zwiększyłoby nakład pracy bez wymiernej korzyści dla kompilatora jednoetapowego. W kompilatorze produkcyjnym AST jest niezbędny dla optymalizacji, wielu przebiegów i lepszych komunikatów błędów.

---

## 9. Szczegóły LLVM IR

### Forma SSA

LLVM IR wymaga formy SSA: każdy rejestr (`%N`) jest przypisywany dokładnie jeden raz. Dlatego:

- Zmienne są przechowywane przez `alloca`/`store`/`load` (stack slots).
- Tymczasowe wyniki obliczeń są w kolejnych rejestrach `%1`, `%2`, `%3`...
- Rozgałęzienia i pętle wymagają węzłów `phi` do scalenia wartości z różnych ścieżek.

### Format strings jako stałe globalne

Formatujące ciągi dla `printf`/`scanf` są deklarowane jako globalne stałe:

```llvm
@fmt_i32_p = constant [4 x i8] c"%d\0A\00"   ; printf int + newline
@fmt_f64_p = constant [4 x i8] c"%f\0A\00"   ; printf double + newline
@fmt_str_r = constant [6 x i8] c"%255s\00"   ; scanf string (max 255 znaków)
```

`\0A` to `\n` (newline), `\00` to null terminator. Długość tablicy obejmuje null terminator.

### Float wymaga fpext przed printf

`printf` jest funkcją variadyczną. W ABI x86-64 argumenty variadyczne float są automatycznie rozszerzane do double. LLVM wymaga tego jawnie:

```llvm
%ext = fpext float %f to double
call @printf(..., double %ext)
```

Generator obsługuje to w metodzie `printf_float`, która najpierw emituje `fpext`, a następnie używa formatu `%f` (dla double).

### Deklaracje zewnętrznych funkcji

Na początku pliku `.ll` kompilator deklaruje:

```llvm
declare i32 @printf(i8*, ...)
declare i32 @__isoc99_scanf(i8*, ...)
declare i8* @strcpy(i8*, i8*)
```

`__isoc99_scanf` zamiast `scanf` jest konieczne na systemach Linux z glibc, gdzie zwykłe `scanf` w LLVM IR może nie matchować symbolu z biblioteki.

---

## 10. Tablice

### Deklaracja

```antlr
typeName '[' INT ']' ID             #arrayDecl         ; int[5] tab
typeName '[' INT DOTDOT INT ']' ID  #rangedArrayDecl   ; int[1..3] ranged
```

W LLVM IR obie deklaracje generują ten sam wzorzec — alokację tablicy o rozmiarze fizycznym:

```llvm
%tab = alloca [5 x i32]
%ranged = alloca [3 x i32]   ; 3 = 3 - 1 + 1
```

### Dostęp do elementu — getelementptr

GEP (getelementptr) wylicza wskaźnik do elementu tablicy:

```llvm
%ptr = getelementptr inbounds [5 x i32], [5 x i32]* %tab, i32 0, i32 %idx
```

Pierwszy indeks `i32 0` de-referencjonuje wskaźnik do tablicy; drugi indeks `i32 %idx` wskazuje na element.

### Przeliczenie indeksu — adjustIndex

Dla tablic z zakresem indeksów (np. `int[1..3]`) użytkownik podaje indeks od `startIndex`. Kompilator odejmuje `startIndex` by uzyskać fizyczny indeks:

```llvm
%physical_idx = sub i32 %user_idx, 1   ; dla ranged[1..3]: user=1 → physical=0
```

Metoda `adjustIndex` w `LLVMActions` emituje tę instrukcję tylko gdy `startIndex != 0`.

### Wypisanie całej tablicy — print arr

`print arr` (bez indeksu) drukuje wszystkie elementy. `LLVMGenerator.printArrayAll` generuje kod compile-time unrolled — pętla w Javie iteruje po rozmiarze tablicy i emituje osobne instrukcje GEP + load + printf dla każdego elementu. Nie ma pętli w LLVM IR. Jest to dopuszczalne dla małych tablic (rozmiar znany w czasie kompilacji).

---

## 11. Short-circuit evaluation — OR i AND

### Problem

`a OR b` w normalnej ewaluacji oblicza oba argumenty. Short-circuit oznacza, że jeśli `a = true`, `b` nie jest obliczane (i nie są wykonywane żadne efekty uboczne). Analogicznie `a AND b`: jeśli `a = false`, `b` jest pomijane.

### Implementacja przez warunkowe skoki i phi

**OR:**

```
oblicz left
condBr left, or_true, or_eval
or_eval:
    oblicz right
    br or_end
or_true:
    br or_end
or_end:
    %result = phi i1 [right, or_eval], [true, or_true]
```

Jeśli `left = true`, skaczemy do `or_true`, gdzie wynik to `true` bez obliczania `right`. Jeśli `left = false`, przechodzimy do `or_eval`, obliczamy `right` i wynik to `right`.

**AND:**

```
oblicz left
condBr left, and_eval, and_false
and_eval:
    oblicz right
    br and_end
and_false:
    br and_end
and_end:
    %result = phi i1 [right, and_eval], [false, and_false]
```

### Dlaczego phi, a nie load/store?

Można by zapisać wynik do `alloca` i potem wczytać. Ale `phi` jest idiomatyczne dla SSA i bardziej efektywne — nie wymaga dostępu do pamięci.

### Śledzenie currentBlock

`phi` wymaga podania nazwy bloku, z którego pochodzi każda wartość. Po emitowaniu kodu `right`, aktualny blok może być inny niż etykieta `or_eval` (np. jeśli `right` samo zawierało short-circuit). Dlatego `LLVMGenerator.emitLabel` aktualizuje pole `currentBlock`, a `visitOrOp`/`visitAndOp` zapisują `evalEndBlock = LLVMGenerator.currentBlock` po odwiedzeniu prawego poddrzewa.

### XOR nie ma short-circuit

XOR wymaga wartości obu argumentów do obliczenia wyniku (`a XOR b = a != b`). Implementacja jest prosta:

```llvm
%result = xor i1 %left, %right
```

---

## 12. Konwersja typów

### Dwa mechanizmy: coerce i cast

**`coerce`** — niejawna konwersja wykonywana automatycznie przy przypisaniach i inicjalizacjach. Dozwolona tylko dla poszerzeń bez utraty informacji:

- `int → float32` (sitofp)
- `int → double` (sitofp)
- `float32 → double` (fpext)
- `double → float32` (fptrunc, zawężenie — uwaga na utratę precyzji)

**`cast`** — jawna konwersja przez `(typ) wyrażenie`. Pozwala na więcej:

- `float → int` (fptosi — obcięcie, nie zaokrąglenie)
- `int → bool` (icmp ne 0)
- `bool → int` (zext)
- `bool → float` (zext + sitofp)

### Dlaczego rozróżnienie?

Jawne rzutowanie sygnalizuje programiście, że jest świadomy potencjalnej utraty precyzji (np. `(int) 3.7` daje `3`). Niejawna konwersja działa tylko tam, gdzie wynik jest zawsze poprawny matematycznie lub różnica jest tylko precyzją.

### unifyNumeric — ujednolicenie przed operacją binarną

Przed każdą operacją arytmetyczną lub porównaniem `unifyNumeric` promuje słabszy operand do silniejszego typu:

```
int + float32    → float32 + float32
int + double     → double + double
float32 + double → double + double
```

Dzięki temu `int x = 2; real y = 1.5; real z = x + y` działa bez jawnego rzutowania.

---

## 13. Wejście-Wyjście

### printf

Każdy typ ma własną metodę generatora:

| Metoda | Format | Typ LLVM |
|---|---|---|
| `printf_i32` | `%d\n` | `i32` |
| `printf_double` | `%f\n` | `double` |
| `printf_float` | `%f\n` | `double` (po fpext) |
| `printf_bool` | `%d\n` | `i32` (po zext) |
| `printf_string` | `%s\n` | `i8*` |

Bool jest drukowany jako `0` lub `1` (po `zext i1 → i32`).

### scanf

Wczytywanie działa analogicznie. Dla stringa format `%255s` ogranicza długość do 255 znaków — zabezpieczenie przed przepełnieniem bufora. Każda zmienna `string` ma zaalokowany statyczny bufor `[256 x i8]` na stosie.

Wczytywanie bool wymaga pośredniego kroku: wczytanie jako `i32`, potem konwersja `icmp ne 0` do `i1`.

---

## 14. Typowe pytania obrończe

**Dlaczego wybrałeś ANTLR4 do parsowania?**  
ANTLR4 generuje parser LL(*) z automatyczną obsługą niejednoznaczności przez predykaty. Eliminuje ręczne pisanie parsera, generuje visitor i listener out-of-the-box oraz produkuje czytelny kod Java. Alternatywy (Bison, ręczny RD parser) wymagają znacznie więcej pracy przy porównywalnym rezultacie.

**Dlaczego kod IR jest budowany przez konkatenację stringów?**  
LLVM IR to format tekstowy — wyjściowym formatem jest plik `.ll`. Budowanie przez stringi jest najprostszym podejściem i wystarczającym dla jednoetapowego kompilatora. Alternatywa to LLVM API (przez JNI lub bibliotekę jak LLVM4J), co byłoby znacznie bardziej skomplikowane, wymagałoby natywnych zależności i nie dałoby mierzalnej korzyści przy tej skali projektu.

**Co to jest SSA i dlaczego LLVM tego wymaga?**  
SSA (Static Single Assignment) to forma IR, w której każdy rejestr jest przypisywany dokładnie jeden raz. Upraszcza analizę przepływu danych i umożliwia wiele optymalizacji (stała propagacja, eliminacja martwego kodu). LLVM wymaga SSA, bo jego optymalizator jest zaprojektowany wokół tej właściwości. Kompilator obsługuje SSA przez wzorzec `alloca`/`load`/`store` dla zmiennych (LLVM optymalizator `mem2reg` automatycznie to uprości).

**Dlaczego tablice mają stały rozmiar, a nie dynamiczny?**  
Decyzja projektowa i ograniczenie zakresu. Stały rozmiar znany w czasie kompilacji pozwala na statyczną alokację na stosie (`alloca [N x T]`) i compile-time unrolling pętli wypisywania. Dynamiczne tablice wymagałyby `malloc`/`free`, zarządzania pamięcią i runtime'owych sprawdzeń zakresu.

**Czy jest sprawdzanie zakresu indeksów?**  
Nie w czasie wykonania. Kompilator sprawdza w czasie kompilacji, czy zmienna użyta jako indeks jest typem `int`, ale nie sprawdza, czy wartość mieści się w zakresie tablicy. Wyjście poza zakres spowoduje niezdefiniowane zachowanie w LLVM IR (analogicznie do C). Dodanie sprawdzania zakresu wymagałoby emisji kodu porównania i warunkowego skoku do obsługi błędu.

**Dlaczego `__isoc99_scanf` zamiast `scanf`?**  
Na systemach Linux z glibc (wersja > 2.2.5), `scanf` z formatem `%lf` (dla double) może nie matchować do symbolu `scanf` w bibliotece — zamiast tego glibc eksportuje `__isoc99_scanf` dla standardu C99. LLVM IR deklarując `__isoc99_scanf` unika problemów z linkowaniem.

**Jak działa print dla całej tablicy?**  
`print arr` (bez indeksu) trafia do `visitPrintIdStat`, który wykrywa, że `arr` jest w mapie `arrays`, nie `variables`. Wywołuje `LLVMGenerator.printArrayAll`, która w pętli Java (czas kompilacji) generuje osobne instrukcje GEP + load + printf dla każdego elementu. Rozmiar tablicy jest znany w czasie kompilacji, więc nie potrzeba pętli w IR.

**Co się dzieje gdy wystąpi błąd semantyczny?**  
`semanticError` inkrementuje licznik `errors` i drukuje komunikat na stderr. Kompilacja kontynuuje (visitor nie przerywa), co pozwala znaleźć wiele błędów naraz. Po zakończeniu `visitProg` sprawdza `errors == 0` i tylko wtedy drukuje IR. `Main.java` sprawdza `actions.hasErrors()` i kończy z kodem wyjścia 1.

**Dlaczego `real` i `float64` to ten sam typ LLVM?**  
`real` i `float64` oba mapują na `double` w LLVM IR. To świadoma decyzja: `real` jest aliasem czytelniejszym dla użytkownika, `float64` jest bardziej precyzyjny technicznie. Obie nazwy oznaczają 64-bitową liczbę zmiennoprzecinkową IEEE 754.
