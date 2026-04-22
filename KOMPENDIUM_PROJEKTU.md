# Kompendium wiedzy: MyLang-compiler

## 1. Co to za projekt i po co istnieje

Ten projekt to **kompilator edukacyjnego jezyka MyLang**, ktory zamienia kod z pliku `.ml` na **LLVM IR**.

Najprosciej:
- piszesz program w MyLang,
- kompilator analizuje jego skladnie i sens,
- wypisuje LLVM IR,
- a ten IR mozna uruchomic przez `lli` albo skompilowac przez `clang`.

To jest projekt "pomostowy": pokazuje teorie kompilatorow, ale juz na realnym kodzie i realnym backendzie (LLVM).

---

## 1a. Slownik podstawowych pojec (prosto i praktycznie)

- **Kompilator**: program, ktory tlumaczy kod z jednego jezyka na inny. Tu: z MyLang do LLVM IR.
- **Frontend kompilatora**: czesc, ktora "rozumie" kod zrodlowy (lexer, parser, semantyka).
- **Backend kompilatora**: czesc, ktora emituje kod docelowy (tu: LLVM IR).
- **Lexer (analiza leksykalna)**: dzieli tekst na male elementy zwane tokenami. Opisane w `## 3.2`.
- **Token**: najmniejsza jednostka skladni, np. `INT`, `ID`, `REAL`, `AND`, `=`.
- **Parser (analiza skladniowa)**: buduje strukture kodu (drzewo) na podstawie tokenow i gramatyki. Opisane w `## 3.3`.
- **Gramatyka**: zestaw reguł jezyka; w projekcie to `src/MyLang.g4`.
- **ParseTree**: drzewo skladniowe wygenerowane przez parser ANTLR (uzywane przez visitora).
- **Visitor**: wzorzec przechodzenia po drzewie; tutaj `LLVMActions` ma metody `visitXxx(...)`.
- **Analiza semantyczna**: sprawdza, czy kod ma sens (typy, deklaracje, poprawne uzycie zmiennych). Opisane w `## 3.4`.
- **Tablica symboli (symbol table)**: mapy przechowujace informacje o zmiennych/tablicach; tu `variables` i `arrays`.
- **Type checking**: kontrola zgodnosci typow (np. czy `int` mozna przypisac do `float32`).
- **Coerce**: niejawna konwersja typu potrzebna np. przy przypisaniu (`coerce(...)`).
- **Cast**: jawna konwersja typu zapisana przez uzytkownika, np. `(float32) x` (`cast(...)`).
- **LLVM IR**: posredni kod tekstowy LLVM, ktory jest wynikiem kompilacji (`## 3.5`).
- **SSA (Static Single Assignment)**: forma IR, gdzie kazdy rejestr jest przypisany raz (`%1`, `%2`, ...).
- **Rejestr SSA**: nazwa tymczasowej wartosci w IR, np. `%7`; tworzony przez `nextReg()`.
- **Basic block (blok podstawowy)**: ciag instrukcji bez skokow w srodku; zaczyna sie etykieta.
- **Label (etykieta)**: nazwa bloku, np. `and_eval_0:`.
- **Branch (`br`)**: instrukcja skoku do etykiety lub wyboru miedzy dwiema etykietami.
- **Phi (`phi`)**: instrukcja wybierajaca wartosc w zaleznosci od tego, z ktorego bloku przyszlo wykonanie.
- **Short-circuit**: skrocone liczenie `AND/OR` (czasem prawa strona nie jest liczona). Opisane w `## 4.3`.
- **`alloca` / `load` / `store`**: podstawowe instrukcje pamieci LLVM (rezerwacja, odczyt, zapis).
- **GEP (`getelementptr`)**: oblicza adres elementu, np. `tab[i]`; uzywane mocno przy tablicach (`## 4.4`).
- **ABI**: umowa wywolania funkcji na poziomie binarnym; dlatego np. `float` do `printf` idzie jako `double`.
- **Varargs**: funkcje z zmienna liczba argumentow (np. `printf`), wymagaja poprawnych promocji typow.
- **Runtime**: funkcje zewnetrzne wykorzystywane przez IR, np. `@printf`, `@__isoc99_scanf`, `@strcpy`.

Jak czytac dalej:
- jesli termin jest nowy, wroc do tej sekcji,
- w kolejnych rozdzialach terminy sa juz uzywane "w biegu" na realnych fragmentach kodu.

## 2. Jak dziala caly proces (widok 10 000 m)

Pelny przeplyw:

`plik .ml -> Lexer (ANTLR) -> Parser (ANTLR) -> ParseTree -> LLVMActions (visitor) -> LLVMGenerator -> stdout (.ll)`

Gdzie to siedzi w kodzie:
- uruchomienie i spinanie etapow: `src/Main.java`
- tokeny + gramatyka: `src/MyLang.g4`
- walidacja semantyczna i decyzje "co emitowac": `src/LLVMActions.java`
- generowanie instrukcji LLVM: `src/LLVMGenerator.java`
- bledy lexer/parser: `src/ErrorListener.java`

---

## 3. Etap po etapie: teoria + realizacja w tym projekcie

## 3.1 Etap A: Wczytanie pliku i start pipeline

**Teoria**
- Kompilator musi najpierw zbudowac strumien znakow z pliku.

**Realizacja**
- `Main.main(...)` w `src/Main.java` robi `CharStreams.fromFileName(args[0])`.
- Jesli plik nie istnieje, kompilator konczy sie komunikatem bledu.

**Co to daje**
- Dalsze etapy zawsze dostaja ten sam, standardowy `CharStream`.

---

## 3.2 Etap B: Lexer (znaki -> tokeny)

**Teoria**
- Lexer zamienia surowy tekst na tokeny (`INT`, `ID`, `REAL`, itp.).

**Realizacja**
- Reguly tokenow sa w `src/MyLang.g4` (dolna czesc pliku).
- Krytyczny fragment: `DOTDOT` i `REAL` musza wspolgrac dla zapisu tablic `1..5`.

Przyklad:

```mylang
int[1..5] arr
```

musi byc czytane jako:
- `INT` `[` `INT(1)` `DOTDOT(..)` `INT(5)` `]` `ID(arr)`

a nie jako `REAL(1.)` + reszta.

**Dlaczego to wazne**
- Jesli lexer zle potnie tokeny, parser juz niczego nie naprawi.

---

## 3.3 Etap C: Parser (tokeny -> drzewo skladniowe)

**Teoria**
- Parser buduje drzewo zgodne z gramatyka i priorytetami operatorow.

**Realizacja**
- `Main` tworzy `MyLangParser` i wywoluje `parser.prog()`.
- Gramatyka i etykiety alternatyw (`#addOp`, `#andOp`, ...) sa w `src/MyLang.g4`.
- ANTLR generuje m.in. `src/gen/MyLangParser.java`, `src/gen/MyLangVisitor.java`, `src/gen/MyLangBaseVisitor.java`.

**Praktyczny efekt etykiet**
- Dla `#addOp` powstaje metoda `visitAddOp(...)`.
- Dla `#andOp` powstaje `visitAndOp(...)`.
- To pozwala pisac semantyke bez recznego rozpoznawania typu operatora po tokenie.

---

## 3.4 Etap D: Analiza semantyczna (czy program ma sens)

**Teoria**
- Program moze byc skladniowo poprawny, ale semantycznie bledny (np. niezadeklarowana zmienna).

**Realizacja**
- `src/LLVMActions.java` dziedziczy po `MyLangBaseVisitor<Value>`.
- Trzyma dwie mapy symboli:
  - `variables`: skalarne zmienne,
  - `arrays`: tablice z metadanymi (`ArrayInfo`).
- Bledy semantyczne zglasza `semanticError(...)`.

**Najwazniejsza konwencja visitora**
- wyrazenia zwracaja `Value(name, type)`,
- instrukcje zwracaja `null`.

To bardzo porzadkuje kod, bo kazdy `visit...` ma jasny kontrakt.

---

## 3.5 Etap E: Emisja LLVM IR

**Teoria**
- Backend zamienia operacje jezyka na instrukcje niskiego poziomu (tu: LLVM IR).

**Realizacja**
- `src/LLVMGenerator.java` ma dwa bufory:
  - `header_text` (stale globalne, literały string),
  - `main_text` (instrukcje wewnatrz `@main`).
- Numeracja SSA idzie przez `nextReg()` -> `%1`, `%2`, `%3`, ...
- Na koncu `generate()` skleja calosc i zwraca gotowy modul.

**Kluczowa zasada LLVM**
- Kazda instrukcja zwracajaca wartosc musi miec numer rejestru.

---

## 4. Przyklady "od kodu do IR" (co, gdzie i jak)

## 4.1 Deklaracja i przypisanie int

Kod MyLang:

```mylang
int x = 5
print x
```

Gdzie to jest obslugiwane:
- `visitVarDecl(...)` w `src/LLVMActions.java`
- `declare(...)`, `assign(...)`, `load(...)`, `printf_i32(...)` w `src/LLVMGenerator.java`

Typowy IR (uproszczony):

```llvm
%x = alloca i32
store i32 5, i32* %x
%1 = load i32, i32* %x
%2 = call i32 (i8*, ...) @printf(..., i32 %1)
```

Co sie dzieje krok po kroku:
1. Visitor widzi `int x = 5` i dodaje `x` do `variables`.
2. Generator emituje `alloca i32` dla `%x`.
3. Literal `5` to `Value("5", INT)`.
4. Visitor wywoluje `assign`, ktory robi `store`.
5. Dla `print x` robi `load` i potem `printf_i32`.

---

## 4.2 Mieszane typy i promocja

Kod MyLang:

```mylang
int a = 2
float32 b = 1.5
print a + b
```

Gdzie:
- `visitAddOp(...)` i `unifyNumeric(...)` w `src/LLVMActions.java`
- `intToFloat(...)` + `add(...)` + `printf_float(...)` w `src/LLVMGenerator.java`

Typowy IR (uproszczony):

```llvm
%1 = sitofp i32 2 to float
%2 = fadd float %1, 1.5
%3 = fpext float %2 to double
%4 = call i32 (i8*, ...) @printf(..., double %3)
```

Dlaczego `fpext`?
- `printf` jest funkcja wariadyczna, a `float` w ABI C jest promowany do `double`.
- Dlatego `printf_float(...)` najpierw rozszerza `float -> double`.

---

## 4.3 Short-circuit dla AND

Kod MyLang:

```mylang
bool r = a AND b
```

Gdzie:
- `visitAndOp(...)` w `src/LLVMActions.java`
- `condBr(...)`, `emitLabel(...)`, `phi_i1(...)` w `src/LLVMGenerator.java`

Schemat IR:

```llvm
br i1 %left, label %and_eval_0, label %and_false_0
and_eval_0:
  ; licz prawa strone
  br label %and_end_0
and_false_0:
  br label %and_end_0
and_end_0:
  %r = phi i1 [%right, %and_eval_0], [false, %and_false_0]
```

Co to daje:
- Gdy lewa strona jest `false`, prawa nie jest liczona.
- To jest poprawny short-circuit, nie zwykle bitowe "and".

---

## 4.4 Tablice z zakresem indeksow `a..b`

Kod MyLang:

```mylang
int[1..3] t
t[2] = 200
print t
```

Gdzie:
- deklaracja: `visitRangedArrayDecl(...)` w `src/LLVMActions.java`
- metadane: `src/ArrayInfo.java`
- korekcja indeksu: `adjustIndex(...)` w `src/LLVMActions.java`
- adresowanie elementu: `arrayGEP(...)` w `src/LLVMGenerator.java`
- druk calej tablicy: `printArrayAll(...)` (unroll)

Typowy IR dla `t[2] = 200`:

```llvm
%1 = sub i32 2, 1
%2 = getelementptr inbounds [3 x i32], [3 x i32]* %t, i32 0, i32 %1
store i32 200, i32* %2
```

Prosto:
- Uzytkownik mysli indeksami `1..3`.
- LLVM mysli indeksami `0..2`.
- Dlatego jest korekcja `userIndex - startIndex`.

---

## 4.5 String: bufor + kopiowanie

Kod MyLang:

```mylang
string s = "hello"
print s
```

Gdzie:
- `visitVarDecl(...)` i `visitStringLit(...)` w `src/LLVMActions.java`
- `declareString(...)`, `stringLiteralPtr(...)`, `loadString(...)`, `printf_string(...)` w `src/LLVMGenerator.java`

Model w IR:
- `%s` przechowuje wskaznik `i8*`,
- `%s_buf` to lokalny bufor `[256 x i8]`.

Przypisanie do stringa robi kopiowanie `strcpy`, a nie podmiane wskaznika.
To jest bezpieczniejsze dla modelu tego projektu i stabilne przy kolejnych przypisaniach.

---

## 5. Obsluga bledow: kiedy i gdzie

## 5.1 Bledy skladniowe (lexer/parser)

- Klasa: `src/ErrorListener.java`
- Podpieta w `Main` do lexera i parsera.
- Przy bledzie ustawia `hasErrors = true` i wypisuje komunikat z lokalizacja.

Przyklad komunikatu:

```text
Blad skladniowy [linia 3:7]: nieoczekiwany token 'xyz'
```

## 5.2 Bledy semantyczne (visitor)

- Klasa: `src/LLVMActions.java`
- Metoda: `semanticError(...)`

Przyklady:
- niezadeklarowana zmienna,
- niezgodnosc typow przy przypisaniu,
- zly typ indeksu tablicy,
- niedozwolone rzutowanie.

Wazne:
- gdy sa bledy semantyczne, IR nie powinien byc traktowany jako poprawny wynik kompilacji.

---

## 6. Dlaczego ten kompilator "dziala" - mechanika sukcesu

On dziala stabilnie, bo ma 3 warstwy, ktore sie nie mieszaja:

1. **ANTLR** pilnuje formy (skladni).
2. **LLVMActions** pilnuje sensu (typow i deklaracji).
3. **LLVMGenerator** pilnuje poprawnego "zapisu maszynowego" (IR + SSA).

Ta separacja powoduje, ze:
- latwiej debugowac,
- latwiej rozszerzac jezyk,
- latwiej unikac regresji.

---

## 7. Co jest odtworcze, a co tworcze (bardzo jasno)

## 7.1 Czesc odtworcza (z materialow/teorii)

To rzeczy, ktore sa klasycznym standardem:
- pipeline kompilatora: lexer -> parser -> semantyka -> generacja kodu,
- ANTLR4 i visitor pattern,
- tablica symboli,
- podstawowe mapowanie typow na LLVM,
- raportowanie bledow etapami.

**Dlaczego odtworcze?**
- bo to sa znane, podrecznikowe techniki i wzorce z kursu.

## 7.2 Czesc tworcza (autorskie decyzje projektu)

To rzeczy "jak dokladnie to zrobiono tutaj":
- dwa bufory IR: `header_text` + `main_text`,
- konkretny styl short-circuit przez etykiety i `phi_i1`,
- tablice zakresowe `a..b` przez `ArrayInfo` + `adjustIndex`,
- model stringa (`%id` + `%id_buf` 256B + `strcpy`),
- compile-time unroll dla `print arr`.

**Dlaczego tworcze?**
- bo te decyzje mozna bylo zrobic inaczej, a tutaj wybrano konkretne rozwiazania pod prostote i czytelnosc projektu.

## 7.3 Jednozdaniowe podsumowanie

- odtworcze = "co mowi teoria",
- tworcze = "jak teoria zostala przekuta na konkretna implementacje w tym repo".

---

## 8. Jak rozszerzyc jezyk i niczego nie popsuc

Przyklad: nowy operator.

1. Dopisz regule i etykiete w `src/MyLang.g4`.
2. Uruchom `make gen` (odswiezenie `src/gen/`).
3. Dodaj `visitNowyOperator(...)` w `src/LLVMActions.java`.
4. Jesli potrzeba, dopisz metode emitujaca w `src/LLVMGenerator.java`.
5. Sprawdz na `make ir FILE=...` i potem `make test`.

Dlaczego ta kolejnosc:
- bez `make gen` Java nie ma nowych interfejsow visitora,
- bez zmian w visitorze parser zrozumie skladnie, ale semantyka bedzie "pusta",
- bez zmian w generatorze mozesz miec semantyke, ale bez poprawnego IR.

---

## 9. Najwazniejsze pulapki i szybka diagnoza

1. **Czerwone klasy `MyLangVisitor/MyLangParser` w IDE**
   - zwykle problem konfiguracji source root/dependencies, nie samej logiki kompilatora.

2. **LLVM: "instruction expected to be numbered"**
   - brak `nextReg()` dla instrukcji zwracajacej wartosc.

3. **Nagle psuje sie zakres tablic `1..5`**
   - sprawdz tokeny `DOTDOT` i `REAL` w `src/MyLang.g4`.

4. **`AND/OR` daje zly wynik uboczny**
   - sprawdz branch/label/phi i aktualizacje `currentBlock` przez `emitLabel(...)`.

5. **Float wypisuje sie dziwnie albo wali typem w IR**
   - sprawdz, czy jest `fpext` przed `printf`.

---

## 10. Mini mapa plikow do nauki projektu

Najpierw czytaj:
1. `README.md` - co umie jezyk i jak uruchomic.
2. `src/MyLang.g4` - co parser w ogole rozumie.
3. `src/Main.java` + `src/ErrorListener.java` - start i diagnostyka.
4. `src/LLVMActions.java` - glowna logika kompilacji.
5. `src/LLVMGenerator.java` - finalny ksztalt IR.

Na koniec:
- `ARCHITEKTURA.md` - wersja architektoniczna,
- `CLAUDE.md` - wersja operacyjna dla agenta,
- `tests/` - realne programy pokazujace zachowanie jezyka.

---

## 11. Krotki "film" myslowy: co dzieje sie dla jednego programu

Program:

```mylang
int x = 5
int y = 3
bool big = x > y
print big
```

Przebieg:
1. Lexer rozpoznaje tokeny (`int`, `ID`, `=`, `INT`, ...).
2. Parser uklada drzewo zgodnie z reguly `stat` i `expr`.
3. Visitor:
   - deklaruje `x`, `y`, `big`,
   - dla `x > y` robi `load`, `icmp sgt`,
   - dla `print big` robi konwersje `i1 -> i32` i `printf`.
4. Generator oddaje gotowe LLVM IR z `define i32 @main()`.
5. `lli` wykonuje IR i wypisuje `1`.

To jest pelen dowod, ze teoria kompilacji zostala poprawnie zastosowana w praktyce tego projektu.

