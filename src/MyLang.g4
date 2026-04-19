// ════════════════════════════════════════════════════════════════════════════
//  Gramatyka języka MyLang (ANTLR4)
//
//  Generuje: MyLangLexer.java, MyLangParser.java, MyLangBaseVisitor.java
//  Użyj:     java -jar antlr-4.13.1-complete.jar -visitor -no-listener MyLang.g4
//
//  Projektowe decyzje:
//  ┌─────────────────────────────────────────────────────────────────────────┐
//  │ 1. PIERWSZEŃSTWO OPERATORÓW                                             │
//  │    Reguły gramatyki odzwierciedlają pierwszeństwo (wyższy priorytet     │
//  │    = głębiej w drzewie). Od najniższego:                                │
//  │       orExpr < xorExpr < andExpr < notExpr < cmpExpr < addExpr         │
//  │       < mulExpr < castExpr < unaryExpr < primaryExpr                   │
//  │                                                                         │
//  │ 2. NAZWANE ALTERNATYWY (#eqOp, #addOp, ...)                             │
//  │    ANTLR4 generuje osobną metodę visitXxx dla każdej etykiety.          │
//  │    Bez etykiet wszystkie alternatywy w regule trafiają do jednej metody, │
//  │    co utrudnia implementację (trzeba rozróżniać przez ctx.op).           │
//  │    Używamy nazw zamiast op=(...) – to drugie może zawieść w ANTLR 4.13. │
//  │                                                                         │
//  │ 3. TOKEN DOTDOT ('..')                                                  │
//  │    Musi być zdefiniowany przed REAL. Reguła REAL wymaga cyfr po kropce   │
//  │    ([0-9]+ '.' [0-9]+), żeby "1..5" było poprawnie tokenizowane jako    │
//  │    INT('..')INT zamiast REAL('.5').                                      │
//  │                                                                         │
//  │ 4. KOLEJNOŚĆ REGUŁ stat                                                 │
//  │    ANTLR dopasowuje pierwszą pasującą alternatywę.                       │
//  │    - rangedArrayDecl przed arrayDecl (oba zaczynają się od typeName '[') │
//  │    - arrayAssign przed assign (różni się 2. tokenem: '[' vs '=')         │
//  │    - readArrayStat przed readStat (różni się obecnością '[')             │
//  │    - printIdStat przed printStat (by 'print x' trafiło do printIdStat)  │
//  │                                                                         │
//  │ 5. arrayRef przed idRef w primaryExpr                                   │
//  │    ID '[' expr ']' musi być przed samym ID, inaczej ANTLR dopasowałby   │
//  │    'arr' jako idRef i zgłosiłby błąd na '['.                            │
//  └─────────────────────────────────────────────────────────────────────────┘
// ════════════════════════════════════════════════════════════════════════════

grammar MyLang;

// ─── Reguła startowa ─────────────────────────────────────────────────────────
// Program to sekwencja linii, każda zawiera opcjonalną instrukcję.
// NEWLINE jako separator (nie terminator) pozwala na puste linie.

prog: (stat? NEWLINE)* EOF ;

// ─── Instrukcje ──────────────────────────────────────────────────────────────

stat
    // Tablice: rangedArrayDecl musi być przed arrayDecl (oba zaczynają się od typeName '[')
    : typeName '[' INT DOTDOT INT ']' ID  #rangedArrayDecl   // int[1..5] arr
    | typeName '[' INT ']' ID             #arrayDecl          // int[5] arr

    // Zmienne skalarne
    | typeName ID ('=' expr)?             #varDecl            // int x = 5

    // Przypisania: arrayAssign przed assign (różny 2. token)
    | ID '[' expr ']' '=' expr           #arrayAssign        // arr[i] = expr
    | ID '=' expr                         #assign             // x = expr

    // Odczyt: readArrayStat przed readStat
    | 'read' ID '[' expr ']'             #readArrayStat      // read arr[i]
    | 'read' ID                           #readStat           // read x

    // Wypisanie: printIdStat przed printStat (by 'print x' i 'print arr' trafiły tu)
    | 'print' ID                          #printIdStat        // print x  lub  print arr
    | 'print' expr                        #printStat          // print x + 1  lub  print arr[i]
    ;

// ─── Typy ────────────────────────────────────────────────────────────────────

typeName
    : 'int'     #typeInt
    | 'real'    #typeReal
    | 'float32' #typeFloat32
    | 'float64' #typeFloat64
    | 'bool'    #typeBool
    | 'string'  #typeString
    ;

// ─── Wyrażenia (hierarchia pierwszeństwa, od najniższego do najwyższego) ─────
//
// Każdy poziom definiuje jeden lub dwa operatory. "Pass" to przejście
// do następnego, wyższego poziomu bez żadnego operatora.

expr: orExpr ;

// Poziom 1: OR (najniższy priorytet, lewostronne)
orExpr
    : orExpr 'OR' xorExpr    #orOp
    | xorExpr                 #orPass
    ;

// Poziom 2: XOR (lewostronne, bez short-circuit)
xorExpr
    : xorExpr 'XOR' andExpr  #xorOp
    | andExpr                  #xorPass
    ;

// Poziom 3: AND (lewostronne, z short-circuit)
andExpr
    : andExpr 'AND' notExpr  #andOp
    | notExpr                  #andPass
    ;

// Poziom 4: NOT (jednoargumentowy, prawostronne)
notExpr
    : 'NOT' notExpr           #notOp
    | cmpExpr                  #notPass
    ;

// Poziom 5: Porównania (nie są lewostronne – nie można pisać a < b < c)
// Używamy osobnych etykiet zamiast op=(...), bo to drugie bywa zawodne w ANTLR 4.13
cmpExpr
    : addExpr EQ  addExpr    #eqOp
    | addExpr NEQ addExpr    #neOp
    | addExpr LE  addExpr    #leOp
    | addExpr GE  addExpr    #geOp
    | addExpr LT  addExpr    #ltOp
    | addExpr GT  addExpr    #gtOp
    | addExpr                 #cmpPass
    ;

// Poziom 6: Dodawanie i odejmowanie (lewostronne)
addExpr
    : addExpr '+' mulExpr    #addOp
    | addExpr '-' mulExpr    #subOp
    | mulExpr                 #addPass
    ;

// Poziom 7: Mnożenie i dzielenie (lewostronne)
mulExpr
    : mulExpr '*' castExpr   #mulOp
    | mulExpr '/' castExpr   #divOp
    | castExpr                #mulPass
    ;

// Poziom 8: Rzutowanie (prawostronne – można pisać (int)(real)x)
// Prawostronne: ANTLR4 parsuje castExpr jako prawą podregułę
castExpr
    : '(' typeName ')' castExpr  #castOp
    | unaryExpr                   #castPass
    ;

// Poziom 9: Jednoargumentowy minus (prawostronne)
unaryExpr
    : '-' primaryExpr   #negOp
    | primaryExpr        #unaryPass
    ;

// Poziom 10: Atomy (najwyższy priorytet)
// arrayRef MUSI być przed idRef – inaczej 'arr' byłoby parsowane jako ID,
// a następny '[' powodowałby błąd składniowy
primaryExpr
    : INT                #intLit      // 42
    | REAL               #realLit     // 3.14
    | 'true'             #trueLit     // true
    | 'false'            #falseLit    // false
    | STRING_LIT         #stringLit   // "hello"
    | ID '[' expr ']'    #arrayRef    // arr[i]   ← przed idRef!
    | ID                 #idRef       // x
    | '(' expr ')'       #parenExpr   // (x + y)
    ;

// ─── Tokeny – operatory porównania ───────────────────────────────────────────
// Dłuższe tokeny MUSZĄ być przed krótszymi (maximal munch):
// '<=' przed '<', '>=' przed '>', '==' przed '='

EQ  : '==' ;
NEQ : '!=' ;
LE  : '<=' ;
GE  : '>=' ;
LT  : '<'  ;
GT  : '>'  ;

// ─── Token zakresu tablicy ────────────────────────────────────────────────────
// '..' musi być osobnym tokenem, by '1..5' było tokenizowane jako INT DOTDOT INT.
// Gdyby REAL dopuszczał '1.' (bez cyfr po kropce), '1..5' stałoby się REAL('.5') – błąd!

DOTDOT : '..' ;

// ─── Tokeny literałów ─────────────────────────────────────────────────────────
// REAL musi być PRZED INT (maximal munch: '3.14' → REAL, nie INT+reszta)
// REAL wymaga cyfr po kropce: [0-9]+, żeby '1..' nie było tokenizowane jako REAL '1.'

REAL       : [0-9]+ '.' [0-9]+ ;   // 3.14, 1.0, 0.5
INT        : [0-9]+              ;   // 42, 0, 100
STRING_LIT : '"' (~["\\\r\n])* '"' ; // "hello", "foo bar"
ID         : [a-zA-Z_][a-zA-Z0-9_]* ; // x, myVar, _tmp

// ─── Tokeny ignorowane ───────────────────────────────────────────────────────
NEWLINE      : '\r'? '\n'           ;   // separator linii (NIE ignorowany – używany w prog)
WS           : [ \t]+    -> skip    ;   // spacje i tabulatory
LINE_COMMENT : '//' ~[\r\n]* -> skip ;  // komentarze jednolinijkowe
