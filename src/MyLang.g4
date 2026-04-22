grammar MyLang;

prog
    : (stat? NEWLINE)* EOF
    ;

stat
    : typeName '[' INT DOTDOT INT ']' ID  #rangedArrayDecl
    | typeName '[' INT ']' ID             #arrayDecl
    | typeName ID ('=' expr)?             #varDecl
    | ID '[' expr ']' '=' expr            #arrayAssign
    | ID '=' expr                         #assign
    | 'read' ID '[' expr ']'              #readArrayStat
    | 'read' ID                           #readStat
    | 'print' ID                          #printIdStat
    | 'print' expr                        #printStat
    ;

typeName
    : 'int'     #typeInt
    | 'real'    #typeReal
    | 'float32' #typeFloat32
    | 'float64' #typeFloat64
    | 'bool'    #typeBool
    | 'string'  #typeString
    ;

expr: orExpr;

orExpr
    : orExpr 'OR' xorExpr  #orOp
    | xorExpr               #orPass
    ;

xorExpr
    : xorExpr 'XOR' andExpr  #xorOp
    | andExpr                  #xorPass
    ;

andExpr
    : andExpr 'AND' notExpr  #andOp
    | notExpr                  #andPass
    ;

notExpr
    : 'NOT' notExpr  #notOp
    | cmpExpr         #notPass
    ;

cmpExpr
    : addExpr EQ  addExpr  #eqOp
    | addExpr NEQ addExpr  #neOp
    | addExpr LE  addExpr  #leOp
    | addExpr GE  addExpr  #geOp
    | addExpr LT  addExpr  #ltOp
    | addExpr GT  addExpr  #gtOp
    | addExpr               #cmpPass
    ;

addExpr
    : addExpr '+' mulExpr  #addOp
    | addExpr '-' mulExpr  #subOp
    | mulExpr               #addPass
    ;

mulExpr
    : mulExpr '*' castExpr  #mulOp
    | mulExpr '/' castExpr  #divOp
    | castExpr               #mulPass
    ;

castExpr
    : '(' typeName ')' castExpr  #castOp
    | unaryExpr                   #castPass
    ;

unaryExpr
    : '-' primaryExpr  #negOp
    | primaryExpr       #unaryPass
    ;

primaryExpr
    : INT              #intLit
    | REAL             #realLit
    | 'true'           #trueLit
    | 'false'          #falseLit
    | STRING_LIT       #stringLit
    | ID '[' expr ']'  #arrayRef
    | ID               #idRef
    | '(' expr ')'     #parenExpr
    ;

EQ: '==';
NEQ: '!=';
LE: '<=';
GE: '>=';
LT: '<';
GT: '>';

DOTDOT: '..';

REAL
    : [0-9]+ '.' [0-9]+
    ;

INT
    : [0-9]+
    ;

STRING_LIT
    : '"' (~["\\\r\n])* '"'
    ;

ID
    : [a-zA-Z_][a-zA-Z0-9_]*
    ;

NEWLINE
    : '\r'? '\n'
    ;

WS
    : [ \t]+     -> skip
    ;

LINE_COMMENT
    : '//' ~[\r\n]* -> skip
    ;
